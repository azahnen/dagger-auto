package com.github.azahnen.dagger;

import com.github.azahnen.dagger.annotations.AutoMultiBind;
import com.github.azahnen.dagger.annotations.AutoMultiBind.Type;
import java.util.Map;
import java.util.Optional;

class SingleBinding implements Binding {
  final String packageName;
  final String implementationFullName;
  final String implementationSimpleName;
  final String interfaceFullName;
  final String interfaceSimpleName;
  final Optional<AutoMultiBind.Type> multiBind;
  final Optional<String> multiBindKey;
  final boolean multiBindSameModule;
  final boolean multiBindOtherModule;
  final Map<String, String> injections;

  SingleBinding(String packageName, String implementationFullName,
      String implementationSimpleName,
      String interfaceFullName, String interfaceSimpleName,
      Optional<Type> multiBind, Optional<String> multiBindKey, boolean multiBindSameModule,
      boolean multiBindOtherModule, Map<String, String> injections) {
    this.packageName = packageName;
    this.interfaceFullName = interfaceFullName;
    this.interfaceSimpleName = interfaceSimpleName;
    this.implementationFullName = implementationFullName;
    this.implementationSimpleName = implementationSimpleName;
    this.multiBind = multiBind;
    this.multiBindKey = multiBindKey;
    this.multiBindSameModule = multiBindSameModule;
    this.multiBindOtherModule = multiBindOtherModule;
    this.injections = injections;
  }

  @Override
  public String getPackage() {
    return packageName;
  }

  @Override
  public String getInterface() {
    return interfaceFullName;
  }

}
