package com.github.azahnen.dagger;

import java.util.List;

class Module {

  final String packageName;
  final String moduleName;
  final List<Binding> bindings;
  final boolean single;
  final boolean encapsulate;

  public Module(String packageName, String moduleName, List<Binding> bindings, boolean single,
      boolean encapsulate) {
    this.packageName = packageName;
    this.moduleName = moduleName;
    this.bindings = bindings;
    this.single = single;
    this.encapsulate = encapsulate;
  }

  String qualifiedName() {
    return String.format("%s.%s", packageName, moduleName);
  }
}
