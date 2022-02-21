package com.github.azahnen.dagger;

public interface Binding {

  String getPackage();

  String getInterface();

  default String getInterfaceLazy() {
    return getInterface();
  }
}
