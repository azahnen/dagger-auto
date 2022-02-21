package com.github.azahnen.dagger.annotations;

public @interface AutoMultiBind {

  enum Type {SET, STRING_MAP, CLASS_MAP}

  Type value() default Type.SET;

  boolean lazy() default true;
}
