package com.github.azahnen.dagger;

import com.github.azahnen.dagger.annotations.AutoMultiBind.Type;

class MultiBinding implements Binding {
  final String packageName;
  final String interfaceFullName;
  final String interfaceSimpleName;
  final Type multiBind;

  MultiBinding(
      String packageName, String interfaceFullName, String interfaceSimpleName, Type multiBind) {
    this.packageName = packageName;
    this.interfaceFullName = interfaceFullName;
    this.interfaceSimpleName = interfaceSimpleName;
    this.multiBind = multiBind;
  }

  @Override
  public String getPackage() {
    return packageName;
  }

  @Override
  public String getInterface() {
    switch (multiBind) {
      case STRING_MAP:
        return String.format("java.util.Map<String, %s>", interfaceFullName);
      case CLASS_MAP:
        return String.format("java.util.Map<Class<?>, %s>", interfaceFullName);
      case SET:
      default:
        return String.format("java.util.Set<%s>", interfaceFullName);
    }
  }
}
