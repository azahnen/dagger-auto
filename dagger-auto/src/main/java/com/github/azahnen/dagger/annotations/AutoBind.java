package com.github.azahnen.dagger.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
public @interface AutoBind {

  Class<?>[] interfaces() default {};

  String mapKeyString() default "";

  Class<?> mapKeyClass() default Void.class;
}
