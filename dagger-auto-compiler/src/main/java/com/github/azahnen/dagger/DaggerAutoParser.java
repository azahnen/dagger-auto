package com.github.azahnen.dagger;

import com.github.azahnen.dagger.annotations.AutoBind;
import com.github.azahnen.dagger.annotations.AutoModule;
import com.github.azahnen.dagger.annotations.AutoMultiBind;
import com.github.azahnen.dagger.annotations.AutoMultiBind.Type;
import java.lang.annotation.Annotation;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.inject.Inject;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

class DaggerAutoParser {

  private final Map<Class<? extends Annotation>, TypeElement> annotationTypes;
  private final ProcessingEnvironment processingEnv;

  DaggerAutoParser(
      Map<Class<? extends Annotation>, TypeElement> annotationTypes,
      ProcessingEnvironment processingEnv) {
    this.annotationTypes = annotationTypes;
    this.processingEnv = processingEnv;
  }

  List<Module> parse(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
    List<Module> modules = new ArrayList<>();

    Set<? extends Element> autoModuleElements =
        roundEnvironment.getElementsAnnotatedWith(annotationTypes.get(AutoModule.class));
    Set<? extends Element> autoBindElements =
        roundEnvironment.getElementsAnnotatedWith(annotationTypes.get(AutoBind.class));
    Set<? extends Element> autoMultiBindElements =
        roundEnvironment.getElementsAnnotatedWith(annotationTypes.get(AutoMultiBind.class));

    List<Module> predefinedModules = parseModules(autoModuleElements);

    List<Binding> bindings = parseBindings(annotations, autoBindElements, autoMultiBindElements);

    if (predefinedModules.size() == 1 && predefinedModules.get(0).single) {
      predefinedModules.get(0).bindings.addAll(bindings);

      modules.add(predefinedModules.get(0));
    } else if (!bindings.isEmpty()) {
      List<Module> mods =
          bindings.stream().collect(Collectors.groupingBy(Binding::getPackage)).entrySet().stream()
              .map(
                  entry -> {
                    Module module =
                        predefinedModules.stream()
                            .filter(module1 -> Objects.equals(module1.packageName, entry.getKey()))
                            .findFirst()
                            .orElseGet(
                                () ->
                                    new Module(
                                        entry.getKey(),
                                        "AutoBindings",
                                        new ArrayList<>(),
                                        false,
                                        false));
                    module.bindings.addAll(entry.getValue());

                    return module;
                  })
              .collect(Collectors.toList());
      modules.addAll(mods);
    }

    return modules;
  }

  private List<Module> parseModules(Set<? extends Element> autoModuleElements) {
    return autoModuleElements.stream()
        .map(
            element -> {
              // TODO: annotation in module-info is ignored by processor
              String packageName =
                  element.getKind() == ElementKind.MODULE
                      ? getAnnotationValue(element, AutoModule.class, "pkg", String.class)
                          .filter(
                              pkg ->
                                  !pkg.isBlank()
                                      && Objects.nonNull(
                                          processingEnv.getElementUtils().getPackageElement(pkg)))
                          .orElseThrow(
                              () ->
                                  new IllegalStateException(
                                      "Invalid @AutoModule pkg for " + element.toString()))
                      : processingEnv
                          .getElementUtils()
                          .getPackageOf(element)
                          .getQualifiedName()
                          .toString();
              String moduleName =
                  getAnnotationValue(element, AutoModule.class, "name", String.class)
                      .orElse("AutoBindings");
              // TODO: throw if more than one single
              boolean isSingle =
                  element.getKind() == ElementKind.MODULE
                      || getAnnotationValue(element, AutoModule.class, "single", Boolean.class)
                          .orElse(false);
              boolean isEncapsulate =
                  element.getKind() == ElementKind.MODULE
                      || getAnnotationValue(element, AutoModule.class, "encapsulate", Boolean.class)
                          .orElse(false);

              return new Module(
                  packageName, moduleName, new ArrayList<>(), isSingle, isEncapsulate);
            })
        .collect(Collectors.toUnmodifiableList());
  }

  private List<Binding> parseBindings(
      Set<? extends TypeElement> annotations,
      Set<? extends Element> autoBindElements,
      Set<? extends Element> autoMultiBindElements) {
    return annotations.stream()
        .flatMap(
            annotation -> {
              if (isSame(annotation, AutoBind.class)) {
                return autoBindElements.stream()
                    .flatMap(
                        element -> parseSingleBindings(element, autoMultiBindElements).stream());
              } else if (isSame(annotation, AutoMultiBind.class)) {
                return autoMultiBindElements.stream().map(this::parseMultiBinding);
              }
              return Stream.empty();
            })
        .collect(Collectors.toList());
  }

  private List<SingleBinding> parseSingleBindings(
      Element element, Set<? extends Element> autoMultiBindElements) {
    List<TypeMirror> interfaces =
        getAnnotationValueClassArray(element, AutoBind.class, "interfaces");

    interfaces.forEach(
        intrfc -> {
          if (getSuperTypes(element.asType())
              .noneMatch(superType -> Objects.equals(superType, intrfc))) {
            throw new IllegalStateException(
                "Invalid entry in @AutoBind interfaces, "
                    + intrfc.toString()
                    + " is not implemented/extended by "
                    + element.toString());
          }
        });

    // TypeMirror.equals does not work, so we have to use a map to get distinct values
    Map<String, TypeMirror> bindInterfaces =
        getSuperTypes(element.asType())
            .filter(superType -> interfaces.isEmpty() || interfaces.contains(superType))
            .collect(Collectors.toMap(TypeMirror::toString, Function.identity(), (first, second) -> first));


    return bindInterfaces.values()
        .stream()
        .map(
            bindInterface ->
                parseSingleBinding(
                    processingEnv.getTypeUtils().asElement(bindInterface),
                    element,
                    autoMultiBindElements))
        .collect(Collectors.toList());
  }

  private SingleBinding parseSingleBinding(
      Element bindInterface, Element implementation, Set<? extends Element> autoMultiBindElements) {
    String packageName =
        processingEnv.getElementUtils().getPackageOf(implementation).getQualifiedName().toString();
    String interfaceFullName = getFullNameWithGenericsAsWildcard(bindInterface);
    String implementationFullName = implementation.toString();
    String interfaceSimpleName = bindInterface.getSimpleName().toString();
    String implementationSimpleName =
        implementation.getSimpleName().toString().substring(0, 1).toLowerCase()
            + implementation.getSimpleName().toString().substring(1);
    Optional<Type> multiBind =
        getAnnotationValueEnum(
                bindInterface, AutoMultiBind.class, "value", Type.class, Type::valueOf)
            .or(
                () ->
                    hasAnnotation(bindInterface, AutoMultiBind.class)
                        ? Optional.of(Type.SET)
                        : Optional.empty());
    Optional<String> multiBindKey =
        multiBind.flatMap(type -> getMultiBindKey(type, implementation));
    boolean multiBindSameModule = autoMultiBindElements.contains(bindInterface);
    Map<String, String> injections = getInjections((TypeElement) implementation);

    return new SingleBinding(
        packageName,
        implementationFullName,
        implementationSimpleName,
        interfaceFullName,
        interfaceSimpleName,
        multiBind,
        multiBindKey,
        multiBindSameModule,
        injections);
  }

  private Optional<String> getMultiBindKey(Type multiBind, Element implementation) {
    // TODO: check for duplicate keys
    switch (multiBind) {
      case STRING_MAP:
        String stringKey =
            getAnnotationValue(implementation, AutoBind.class, "mapKeyString", String.class)
                .filter(mapKeyString -> !mapKeyString.isBlank())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Missing @AutoBind mapKeyString for " + implementation.toString()));
        return Optional.of(stringKey);
      case CLASS_MAP:
        String classKey =
            getAnnotationValueClass(implementation, AutoBind.class, "mapKeyClass")
                .filter(mapKeyClass -> !Objects.equals(mapKeyClass, Void.class.getCanonicalName()))
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Missing @AutoBind mapKeyClass for " + implementation.toString()));
        return Optional.of(classKey);
    }
    return Optional.empty();
  }

  // TODO: method and field injections
  private Map<String, String> getInjections(TypeElement element) {
    return processingEnv.getElementUtils().getAllMembers(element).stream()
        .filter(member -> hasAnnotation(member, Inject.class))
        .flatMap(
            member -> {
              if (member.getKind() == ElementKind.CONSTRUCTOR) {
                return ((ExecutableElement) member)
                    .getParameters().stream()
                        .map(
                            variableElement ->
                                new SimpleEntry<>(
                                    variableElement.asType().toString(),
                                    variableElement.getSimpleName().toString()));
              }

              return Stream.empty();
            })
        .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
  }

  private MultiBinding parseMultiBinding(Element bindInterface) {
    String packageName =
        processingEnv.getElementUtils().getPackageOf(bindInterface).getQualifiedName().toString();
    String interfaceFullName = getFullNameWithGenericsAsWildcard(bindInterface);
    String interfaceSimpleName = bindInterface.getSimpleName().toString();
    Type type =
        getAnnotationValueEnum(
                bindInterface, AutoMultiBind.class, "value", Type.class, Type::valueOf)
            .orElse(Type.SET);

    return new MultiBinding(packageName, interfaceFullName, interfaceSimpleName, type);
  }

  private Stream<TypeMirror> getSuperTypes(TypeMirror type) {
    return processingEnv.getTypeUtils().directSupertypes(type).stream()
        .flatMap(superType -> Stream.concat(Stream.of(superType), getSuperTypes(superType)))
        .filter(superType -> !Objects.equals(superType.toString(), "java.lang.Object"));
  }

  private String getFullNameWithGenerics(Element element) {
    return element.asType().toString();
  }
  private String getFullNameWithGenericsAsWildcard(Element element) {
    if (element.asType() instanceof DeclaredType) {
      List<? extends TypeMirror> typeArguments = ((DeclaredType) element.asType()).getTypeArguments();
      String typeString = typeArguments.isEmpty()
          ? ""
          : typeArguments.stream()
          .map(TypeMirror::toString)
          .map(type -> type.length() == 1 ? "?" : type)
          .collect(Collectors.joining(", ", "<", ">"));
      /*int numTypeArguments = ((DeclaredType) element.asType()).getTypeArguments().size();
      if (numTypeArguments > 0) {
        return element.toString()
            + IntStream.range(0, numTypeArguments)
                .mapToObj(i -> "?")
                .collect(Collectors.joining(", ", "<", ">"));
      }*/
      return element.toString() + typeString;
    }
    return element.toString();
  }

  private boolean hasAnnotation(Element element, Class<? extends Annotation> annotation) {
    return Objects.nonNull(element.getAnnotation(annotation));
  }

  private <T> Optional<T> getAnnotationValue(
      Element element, Class<? extends Annotation> annotationType, String name, Class<T> type) {
    return getAnnotationValue(element, annotationType, name)
        .filter(annotationValue -> type.isAssignableFrom(annotationValue.getClass()))
        .map(type::cast);
  }

  private Optional<String> getAnnotationValueClass(
      Element element, Class<? extends Annotation> annotationType, String name) {
    return getAnnotationValue(element, annotationType, name)
        .filter(
            annotationValue ->
                Objects.nonNull(
                    processingEnv.getElementUtils().getTypeElement(annotationValue.toString())))
        .map(
            annotationValue ->
                processingEnv
                    .getElementUtils()
                    .getTypeElement(annotationValue.toString())
                    .getQualifiedName()
                    .toString());
  }

  @SuppressWarnings("unchecked")
  private List<TypeMirror> getAnnotationValueClassArray(
      Element element, Class<? extends Annotation> annotationType, String name) {
    return getAnnotationValue(element, annotationType, name)
        .filter(
            annotationValues ->
                annotationValues instanceof List
                    && ((List<? extends AnnotationValue>) annotationValues)
                        .stream()
                            .allMatch(
                                annotationValue ->
                                    Objects.nonNull(
                                        processingEnv
                                            .getElementUtils()
                                            .getTypeElement(
                                                annotationValue.getValue().toString()))))
        .map(
            annotationValues ->
                ((List<? extends AnnotationValue>) annotationValues)
                    .stream()
                        .map(
                            annotationValue ->
                                processingEnv
                                    .getElementUtils()
                                    .getTypeElement(annotationValue.getValue().toString())
                                    .asType())
                        .collect(Collectors.toList()))
        .orElse(List.of());
  }

  private <T extends Enum<T>> Optional<T> getAnnotationValueEnum(
      Element element,
      Class<? extends Annotation> annotationType,
      String name,
      Class<T> type,
      Function<String, T> parser) {
    return getAnnotationValue(element, annotationType, name)
        .filter(
            annotationValue -> {
              if (annotationValue instanceof VariableElement
                  && ((VariableElement) annotationValue).getKind() == ElementKind.ENUM_CONSTANT) {
                Element enclosingElement =
                    ((VariableElement) annotationValue).getEnclosingElement();
                TypeElement typeElement =
                    processingEnv.getElementUtils().getTypeElement(type.getCanonicalName());
                return processingEnv
                    .getTypeUtils()
                    .isAssignable(enclosingElement.asType(), typeElement.asType());
              }
              return false;
            })
        .map(
            annotationValue -> {
              String enumValue = ((VariableElement) annotationValue).getSimpleName().toString();
              return parser.apply(enumValue);
            });
  }

  private Optional<Object> getAnnotationValue(
      Element element, Class<? extends Annotation> annotationType, String name) {
    return element.getAnnotationMirrors().stream()
        .filter(annotationMirror -> isSame(annotationMirror.getAnnotationType(), annotationType))
        .flatMap(annotationMirror -> annotationMirror.getElementValues().entrySet().stream())
        .filter(entry -> Objects.equals(entry.getKey().getSimpleName().toString(), name))
        .map(entry1 -> entry1.getValue().getValue())
        .findFirst();
  }

  private boolean isSame(TypeElement annotation1, Class<? extends Annotation> annotation2) {
    return isSame(annotation1.asType(), annotation2);
  }

  private boolean isSame(TypeMirror annotation1, Class<? extends Annotation> annotation2) {
    return annotationTypes.containsKey(annotation2)
        && processingEnv
            .getTypeUtils()
            .isSameType(annotation1, annotationTypes.get(annotation2).asType());
  }
}
