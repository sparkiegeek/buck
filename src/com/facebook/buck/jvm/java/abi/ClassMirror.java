/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java.abi;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Sets;
import com.google.common.io.ByteSource;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;

import java.util.SortedSet;

import javax.annotation.Nullable;

class ClassMirror extends ClassVisitor implements Comparable<ClassMirror> {

  private final String fileName;
  private final SortedSet<AnnotationMirror> annotations;
  private final SortedSet<TypeAnnotationMirror> typeAnnotations;
  private final SortedSet<FieldMirror> fields;
  private final SortedSet<InnerClass> innerClasses;
  private final SortedSet<MethodMirror> methods;
  @Nullable
  private OuterClass outerClass;
  private int version;
  private int access;
  @Nullable
  private String signature;
  @Nullable
  private String[] interfaces;
  @Nullable
  private String superName;
  @Nullable
  private String name;

  public ClassMirror(String name) {
    super(Opcodes.ASM5);

    this.fileName = name;
    this.annotations = Sets.newTreeSet();
    this.typeAnnotations = Sets.newTreeSet();
    this.fields = Sets.newTreeSet();
    this.innerClasses = Sets.newTreeSet();
    this.methods = Sets.newTreeSet();
  }

  @Override
  public void visit(
      int version,
      int access,
      String name,
      String signature,
      String superName,
      String[] interfaces) {
    this.name = name;
    this.version = version;
    this.access = access;
    this.signature = signature;
    this.interfaces = interfaces;
    this.superName = superName;
  }

  @Override
  public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
    AnnotationMirror mirror = new AnnotationMirror(desc, visible);
    annotations.add(mirror);
    return mirror;
  }

  @Override
  public AnnotationVisitor visitTypeAnnotation(
      int typeRef, TypePath typePath, String desc, boolean visible) {
    TypeAnnotationMirror mirror = new TypeAnnotationMirror(typeRef, typePath, desc, visible);
    typeAnnotations.add(mirror);
    return mirror;
  }

  @Override
  public FieldVisitor visitField(
      int access,
      String name,
      String desc,
      String signature,
      Object value) {
    if ((access & Opcodes.ACC_PRIVATE) > 0) {
      return super.visitField(access, name, desc, signature, value);
    }

    FieldMirror mirror = new FieldMirror(access, name, desc, signature, value);
    fields.add(mirror);
    return mirror;
  }

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String desc, String signature, String[] exceptions) {

    // Per JVMS8 2.9, "Class and interface initialization methods are invoked
    // implicitly by the Java Virtual Machine; they are never invoked directly from any
    // Java Virtual Machine instruction, but are invoked only indirectly as part of the class
    // initialization process." Thus we don't need to emit a stub of <clinit>.
    if (((access & Opcodes.ACC_PRIVATE) > 0) ||
        (name.equals("<clinit>") && (access & Opcodes.ACC_STATIC) > 0)) {
      return super.visitMethod(access, name, desc, signature, exceptions);
    }

    // Bridge methods are created by the compiler, and don't appear in source. It would be nice to
    // skip them, but they're used by the compiler to cover the fact that type erasure has occurred.
    // Normally the compiler adds these as public methods, but if you're compiling against a stub
    // produced using our ABI generator, we don't want people calling it accidentally. Oh well, I
    // guess it happens IRL too.
    //
    // Synthetic methods are also generated by the compiler, unless it's one of the methods named in
    // section 4.7.8 of the JVM spec, which are "<init>" and "Enum.valueOf()" and "Enum.values".
    // None of these are actually harmful to the ABI, so we allow synthetic methods through.
    // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.7.8
    MethodMirror mirror = new MethodMirror(access, name, desc, signature, exceptions);
    methods.add(mirror);

    return mirror;
  }

  @Override
  public void visitOuterClass(String owner, String name, String desc) {
    outerClass = new OuterClass(owner, name, desc);
    super.visitOuterClass(owner, name, desc);
  }

  @Override
  public void visitInnerClass(String name, String outerName, String innerName, int access) {
    if ((access & Opcodes.ACC_PRIVATE) > 0) {
      return;
    }

    String currentClassName = Preconditions.checkNotNull(this.name);
    if (currentClassName.equals(name) || currentClassName.equals(outerName)) {
      // InnerClasses attributes are normally present for any member class (of any type) that is
      // referenced from code in this class file. However, for stubbing purposes we need only
      // include InnerClasses attributes for the class itself (if it is a member class, so that
      // the compiler can know that), and for the member classes of this class (so that the
      // compiler knows to go looking for them). All of the other ones are only needed at runtime.
      innerClasses.add(new InnerClass(name, outerName, innerName, access));
      super.visitInnerClass(name, outerName, innerName, access);
    }
  }

  @Override
  public int compareTo(ClassMirror o) {
    if (this == o) {
      return 0;
    }

    return fileName.compareTo(o.fileName);
  }

  public boolean isAnonymousOrLocalClass() {
    if (outerClass == null) {
      return false;
    }

    for (InnerClass innerClass : innerClasses) {
      if (innerClass.name.equals(name) && innerClass.outerName == null) {
        return true;
      }
    }

    return false;
  }

  public ByteSource getStubClassBytes() {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(version, access, name, signature, superName, interfaces);

    if (outerClass != null) {
      writer.visitOuterClass(outerClass.owner, outerClass.name, outerClass.desc);
    }

    for (InnerClass inner : innerClasses) {
      writer.visitInnerClass(inner.name, inner.outerName, inner.innerName, inner.access);
    }

    for (AnnotationMirror annotation : annotations) {
      annotation.appendTo(writer);
    }

    for (TypeAnnotationMirror typeAnnotation : typeAnnotations) {
      typeAnnotation.appendTo(writer);
    }

    for (FieldMirror field : fields) {
      field.accept(writer);
    }

    for (MethodMirror method : methods) {
      method.appendTo(writer);
    }
    writer.visitEnd();
    return ByteSource.wrap(writer.toByteArray());
  }

  private static class InnerClass implements Comparable<InnerClass> {

    private final String name;
    @Nullable
    private final String outerName;
    @Nullable
    private final String innerName;
    private final int access;

    public InnerClass(
        String name,
        @Nullable String outerName,
        @Nullable String innerName,
        int access) {
      this.name = name;
      this.outerName = outerName;
      this.innerName = innerName;
      this.access = access;
    }

    @Override
    public int compareTo(InnerClass o) {
      if (this == o) {
        return 0;
      }

      return ComparisonChain.start()
          .compare(name, o.name)
          .compare(Strings.nullToEmpty(outerName), Strings.nullToEmpty(o.outerName))
          .compare(Strings.nullToEmpty(innerName), Strings.nullToEmpty(o.innerName))
          .result();
    }
  }

  private static class OuterClass implements Comparable<OuterClass> {

    private final String owner;
    private final String name;
    private final String desc;

    public OuterClass(String owner, String name, String desc) {
      this.owner = owner;
      this.name = name;
      this.desc = desc;
    }

    @Override
    public int compareTo(OuterClass o) {
      if (this == o) {
        return 0;
      }

      return ComparisonChain.start()
          .compare(owner, o.owner)
          .compare(name, o.name)
          .compare(desc, o.desc)
          .result();
    }
  }
}
