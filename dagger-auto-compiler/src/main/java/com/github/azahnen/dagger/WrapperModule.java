package com.github.azahnen.dagger;

class WrapperModule {

  final Module module;
  final WrapperComponent component;

  public WrapperModule(Module module, WrapperComponent component) {
    this.module = module;
    this.component = component;
  }
}
