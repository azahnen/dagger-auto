package com.github.azahnen.dagger.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

//TODO: ElementType.MODULE, annotation in module-info is ignored by processor
@Target({ElementType.PACKAGE})
public @interface AutoModule {
  String name() default "AutoBindings";
  boolean single() default false;
  //String pkg() default "";
  boolean encapsulate() default false;
  Class<?>[] multiBindings() default {};
}
