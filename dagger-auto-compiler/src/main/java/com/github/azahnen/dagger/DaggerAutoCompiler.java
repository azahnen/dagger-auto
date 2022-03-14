package com.github.azahnen.dagger;

import java.util.AbstractMap.SimpleEntry;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// TODO: methodName -> Binding.getName
// TODO: README.md, not supported for encapsulate: map multibindings, method + field injections
public class DaggerAutoCompiler {

  Map<String, String> compile(List<Module> modules) {
    /*modules.forEach(module -> System.out.println(module.moduleName + "\n  - "
        + module.bindings.stream()
          .filter(binding -> binding instanceof SingleBinding)
          .map(binding -> ((SingleBinding)binding).implementationFullName + " " + ((SingleBinding)binding).injections.toString() + " " + ((SingleBinding) binding).multiBindOtherModule)
          .collect(Collectors.joining("\n  - "))
        + module.bindings.stream()
        .filter(binding -> binding instanceof MultiBinding)
        .map(binding -> ((MultiBinding)binding).interfaceFullName + " MULTI")
        .collect(Collectors.joining("\n  - "))));*/
    List<Module> simpleModules =
        modules.stream().filter(module -> !module.encapsulate).collect(Collectors.toList());
    List<Module> encapsulatedModules =
        modules.stream().filter(module -> module.encapsulate).collect(Collectors.toList());

    Map<String, String> files = new LinkedHashMap<>();
    files.putAll(compileModules(simpleModules, "", false));
    files.putAll(compileEncapsulated(encapsulatedModules));

    return files;
  }

  private Map<String, String> compileEncapsulated(List<Module> modules) {
    Map<String, String> files = new LinkedHashMap<>();

    files.putAll(compileModules(modules, "Encapsulated", true));
    files.putAll(compileWrapperComponents(modules, "EncapsulatedComponent", "Encapsulated"));
    files.putAll(compileWrapperModules(modules, "EncapsulatedComponent", "Encapsulated"));

    return files;
  }

  private Map<String, String> compileModules(
      List<Module> modules, String nameSuffix, boolean encapsulate) {
    return modules.stream()
        .map(
            module -> {
              String fileName = module.qualifiedName() + nameSuffix;
              String fileContent = compileModule(module, nameSuffix, encapsulate);

              return new SimpleEntry<>(fileName, fileContent);
            })
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private Map<String, String> compileWrapperComponents(
      List<Module> modules, String nameSuffix, String moduleNameSuffix) {
    return modules.stream()
        .map(
            module -> {
              String fileName = module.qualifiedName() + nameSuffix;
              String fileContent = compileWrapperComponent(module, nameSuffix, moduleNameSuffix);

              return new SimpleEntry<>(fileName, fileContent);
            })
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private Map<String, String> compileWrapperModules(
      List<Module> modules, String componentNameSuffix, String moduleNameSuffix) {
    return modules.stream()
        .map(
            module -> {
              String fileName = module.qualifiedName();
              String fileContent =
                  compileWrapperModule(module, componentNameSuffix, moduleNameSuffix);

              return new SimpleEntry<>(fileName, fileContent);
            })
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private String compileModule(Module module, String nameSuffix, boolean encapsulate) {
    String singleBindingsString =
        module.bindings.stream()
            .filter(binding -> binding instanceof SingleBinding)
            .map(binding -> compileSingleBinding((SingleBinding) binding))
            .collect(Collectors.joining("\n\n"));

    String multiBindingsString =
        module.bindings.stream()
            .filter(binding -> binding instanceof MultiBinding && !isForeign(module, (MultiBinding) binding))
            .map(
                binding ->
                    encapsulate
                        ? compileMultiBindingForEncapsulatedModule((MultiBinding) binding)
                        : compileMultiBinding((MultiBinding) binding))
            .collect(Collectors.joining("\n\n"));

    //TODO: instead of the changes in the compiler for injected multibindings from another module
    // it might be better to create actual MultiBindings in parser with a flag like foreign
    String multiBindingsStringInjected = encapsulate ? module.bindings.stream()
        .filter(binding -> binding instanceof SingleBinding)
        .flatMap(binding -> ((SingleBinding)binding).injections.entrySet().stream())
        .filter(entry -> isMulti(entry.getKey()) && !isBound(entry.getKey(), module.bindings))
        .map(
            entry ->
                String.format(
                    "@javax.inject.Singleton\n"
                        + "@dagger.Provides\n"
                        + "@dagger.multibindings.ElementsIntoSet\n"
                        + "static %1$s %2$sExternal(%3$s externalMultiBindings) {\n"
                        + "\treturn externalMultiBindings.%2$s()%4$s;\n"
                        + "}",
                    noLazy(entry.getKey()),
                    entry.getValue(),
                    "ExternalMultiBindings",
                    isLazy(entry.getKey()) ? ".get()" : ""))
        .collect(Collectors.joining("\n\n")) : "";

    String externalMultiBindings =
        encapsulate
            ? compileExternalMultiBindingsWrapper(
                module.bindings.stream()
                    .filter(binding -> binding instanceof MultiBinding && !isForeign(module, (MultiBinding) binding))
                    .map(binding -> (MultiBinding) binding)
                    .collect(Collectors.toList()),
            module.bindings.stream()
                .filter(binding -> binding instanceof SingleBinding)
                .flatMap(binding -> ((SingleBinding)binding).injections.entrySet().stream())
                .filter(entry -> isMulti(entry.getKey()) && !isBound(entry.getKey(), module.bindings))
                .collect(Collectors.toList()))
            : "";

    return String.format(
        "package %s;\n\n@dagger.Module\npublic interface %s {\n\n%s\n\n%s\n\n%s\n\n%s\n\n}",
        module.packageName,
        module.moduleName + nameSuffix,
        singleBindingsString,
        multiBindingsString,
        multiBindingsStringInjected,
        externalMultiBindings);
  }

  //TODO
  private boolean isForeign(Module module, MultiBinding multiBinding) {
    return !module.packageName.startsWith("de.ii.xtraplatform.web")
        && (multiBinding.interfaceFullName.endsWith(".Binder")
    || multiBinding.interfaceFullName.endsWith(".ContainerResponseFilter")
    || multiBinding.interfaceFullName.endsWith(".ContainerRequestFilter")
        || multiBinding.interfaceFullName.endsWith(".ExceptionMapper<?>")
        || multiBinding.interfaceFullName.endsWith(".ExceptionMapper")
        || multiBinding.interfaceFullName.endsWith(".ServletRegistration")
        || multiBinding.interfaceFullName.endsWith(".FeatureFormatExtension"));
  }

  // TODO: scope
  private String compileWrapperComponent(
      Module module, String nameSuffix, String moduleNameSuffix) {
    List<Binding> filtered = module.bindings.stream()
        .filter(binding -> binding instanceof SingleBinding || (binding instanceof MultiBinding
            && !isForeign(module,
            (MultiBinding) binding)))
        .collect(Collectors.toList());
    String bindingsString = compileWrapperComponentBindings(filtered);

    String injections =
        module.bindings.stream()
            .filter(binding -> binding instanceof SingleBinding)
            .map(binding -> (SingleBinding) binding)
            .flatMap(
                binding ->
                    binding.injections.entrySet().stream()
                        .filter(
                            entry ->
                                !isMulti(entry.getKey()) && !isBound(entry.getKey(), module.bindings))
                        .map(
                            entry ->
                                String.format(
                                    "@dagger.BindsInstance\n\t\tBuilder %1$s(%2$s %1$s);",
                                    entry.getValue(), entry.getKey())))
            .distinct()
            .collect(Collectors.joining("\n\t\t"));
    String injections2 =
        String.format(
            "@dagger.BindsInstance\n\t\tBuilder externalMultiBindings(%s.ExternalMultiBindings externalMultiBindings);",
            module.qualifiedName() + moduleNameSuffix);

    String builder =
        String.format(
            "\t@dagger.Component.Builder\n\tinterface Builder {\n\n\t\t%s\n\n\t\t%s\n\n\t\t%s build();\n\n\t}",
            injections, injections2, module.moduleName + nameSuffix);

    return String.format(
        "package %s;\n\n@javax.inject.Singleton\n@dagger.Component(modules = {%s.class})\npublic interface %s {\n\n%s\n\n%s\n\n}",
        module.packageName,
        module.qualifiedName() + moduleNameSuffix,
        module.moduleName + nameSuffix,
        bindingsString,
        builder);
  }

  static boolean isBound(String injection, List<Binding> bindings) {
    return bindings.stream()
        .anyMatch(
            binding1 ->
                Objects.equals(binding1.getInterface(), injection)
                    || Objects.equals(
                    toLazy(binding1.getInterface()),
                    injection));
  }
  static boolean isMulti(String injection) {
    return injection.startsWith("java.util.Set<") || injection.startsWith("dagger.Lazy<java.util.Set<");
  }
  static String noMulti(String intrfc) {
    return isMulti(intrfc) ? intrfc.substring(0, intrfc.length()-1).replace("java.util.Set<", "") : intrfc;
  }
  static boolean isLazy(String injection) {
    return injection.startsWith("dagger.Lazy<");
  }
  static String toLazy(String intrfc) {
    return String.format("dagger.Lazy<%s>", intrfc);
  }
  static String noLazy(String intrfc) {
    return isLazy(intrfc) ? intrfc.substring(0, intrfc.length()-1).replace("dagger.Lazy<", "") : intrfc;
  }

  private String compileWrapperModule(
      Module module, String componentNameSuffix, String moduleNameSuffix) {
    String componentName = module.qualifiedName() + componentNameSuffix;
    String daggerComponentName =
        String.format("%s.Dagger%s%s", module.packageName, module.moduleName, componentNameSuffix);
    // TODO
    String injections = "";

    List<Binding> filtered = module.bindings.stream()
        .filter(binding -> binding instanceof SingleBinding || (binding instanceof MultiBinding
            && !isForeign(module,
            (MultiBinding) binding)))
        .collect(Collectors.toList());

    String builder =
        compileWrapperModuleComponentCreator(
            filtered,
            componentName,
            daggerComponentName,
            module.qualifiedName() + moduleNameSuffix);

    String bindingsString = compileWrapperModuleBindings(filtered, componentName);

    return String.format(
        "package %s;\n\n@dagger.Module\npublic interface %s {\n\n%s\n\n%s\n\n}",
        module.packageName, module.moduleName, builder, bindingsString);
  }

  private String compileWrapperComponentBindings(List<Binding> bindings) {
    Set<String> externalMultiBindings = new HashSet<>();

    return bindings.stream()
        .flatMap(
            binding -> {
              if (binding instanceof SingleBinding) {
                SingleBinding singleBinding = (SingleBinding) binding;
                if (singleBinding.multiBind.isPresent()) {
                  if (!externalMultiBindings.contains((singleBinding.interfaceFullName))) {
                    externalMultiBindings.add(singleBinding.interfaceFullName);
                    return Stream.of(compileMultiBindingForWrapperComponent(singleBinding));
                  } /*else if (singleBinding.multiBindOtherModule) {
                    return Stream.of(
                        compileMultiBindingForWrapperComponent(singleBinding));
                  }*/
                  return Stream.empty();
                }

                return Stream.of(compileSingleBindingForWrapperComponent((SingleBinding) binding));
              } else if (binding instanceof MultiBinding) {
                MultiBinding multiBinding = (MultiBinding) binding;
                if (!externalMultiBindings.contains((multiBinding.interfaceFullName))) {
                  externalMultiBindings.add(multiBinding.interfaceFullName);
                  return Stream.of(compileMultiBindingForWrapperComponent(multiBinding));
                }
              }

              return Stream.empty();
            })
        .collect(Collectors.joining("\n\n"));
  }

  private String compileWrapperModuleBindings(List<Binding> bindings, String componentName) {
    Set<String> externalMultiBindings = new HashSet<>();

    return bindings.stream()
        .flatMap(
            binding -> {
              if (binding instanceof SingleBinding) {
                SingleBinding singleBinding = (SingleBinding) binding;
                if (singleBinding.multiBind.isPresent()) {
                  if (!singleBinding.multiBindSameModule
                      && !externalMultiBindings.contains((singleBinding.interfaceFullName))) {
                    externalMultiBindings.add(singleBinding.interfaceFullName);
                    return Stream.of(
                        compileMultiBindingForWrapperModule(singleBinding, componentName));
                  } /*else if (singleBinding.multiBindOtherModule) {
                    return Stream.of(
                        compileMultiBindingForWrapperModule(singleBinding, componentName));
                  }*/
                  return Stream.empty();
                }

                return Stream.of(
                    compileSingleBindingForWrapperModule((SingleBinding) binding, componentName));
              } else if (binding instanceof MultiBinding) {
                MultiBinding multiBinding = (MultiBinding) binding;
                return Stream.of(compileMultiBinding(multiBinding));
              }

              return Stream.empty();
            })
        .collect(Collectors.joining("\n\n"));
  }

  private String compileWrapperModuleComponentCreator(
      List<Binding> bindings,
      String componentName,
      String daggerComponentName,
      String wrapperModuleName) {
    // TODO: other injections
    String injections =
        bindings.stream()
            .flatMap(
                binding -> {
                  if (binding instanceof SingleBinding) {
                    SingleBinding singleBinding = (SingleBinding) binding;
                    return singleBinding.injections.entrySet().stream()
                        .filter(
                            entry ->
                                bindings.stream()
                                    .noneMatch(
                                        binding1 ->
                                            Objects.equals(
                                                binding1.getInterface(), entry.getKey())))
                        .map(entry -> String.format("%s %s", entry.getKey(), entry.getValue()));
                  } else if (binding instanceof MultiBinding) {
                    MultiBinding multiBinding = (MultiBinding) binding;
                    String paramName =
                        multiBinding.interfaceSimpleName.substring(0, 1).toLowerCase()
                            + multiBinding.interfaceSimpleName.substring(1);
                    switch (multiBinding.multiBind) {
                      case STRING_MAP:
                      case CLASS_MAP:
                        throw new IllegalStateException(
                            "Encapsulation of Map multibindings is currently not supported.");
                      case SET:
                      default:
                        return Stream.of(
                            String.format("%s %s", multiBinding.getInterfaceLazy(), paramName));
                    }
                  }
                  return Stream.empty();
                })
            .distinct()
            .collect(Collectors.joining(", "));

    String builderParameters =
        bindings.stream()
            .filter(binding -> binding instanceof SingleBinding)
            .map(binding -> (SingleBinding) binding)
            .flatMap(
                binding ->
                    binding.injections.entrySet().stream()
                        .filter(
                            entry ->
                                !isMulti(entry.getKey()) && !isBound(entry.getKey(), bindings))
                        .map(entry -> String.format("\t.%1$s(%1$s)", entry.getValue())))
            .distinct()
            .collect(Collectors.joining("\n"));

    String externalMultiBindings =
        Stream.concat(bindings.stream()
            .filter(binding -> binding instanceof MultiBinding)
            .map(binding -> (MultiBinding) binding)
            .map(
                binding -> {
                  String methodName =
                      binding.interfaceSimpleName.substring(0, 1).toLowerCase()
                          + binding.interfaceSimpleName.substring(1);
                  return String.format(
                      "\t\t\tpublic %1$s %2$s() {return %2$s;}",
                      binding.getInterfaceLazy(), methodName);
                }),
                bindings.stream()
                    .filter(binding -> binding instanceof SingleBinding)
                    .flatMap(binding -> ((SingleBinding)binding).injections.entrySet().stream())
                    .filter(entry -> isMulti(entry.getKey()) && !isBound(entry.getKey(), bindings))
                    .map(
                        entry -> {
                          return String.format(
                              "\t\t\tpublic %1$s %2$s() {return %2$s;}",
                              entry.getKey(), entry.getValue());
                        })
                )
            .distinct()
            .collect(Collectors.joining("\n"));

    // TODO
    String builderParameters2 =
        String.format(
            "\t.externalMultiBindings(new %s.ExternalMultiBindings () {\n%s\n\t\t})",
            wrapperModuleName, externalMultiBindings);

    return String.format(
        "@javax.inject.Singleton\n@dagger.Provides\nstatic %s create(%s) {\n\treturn %s.builder()\n\t%s\n\t%s\n\t.build();\n}",
        componentName, injections, daggerComponentName, builderParameters, builderParameters2);
  }

  private String compileSingleBinding(SingleBinding binding) {
    String intoSetOrMap = compileIntoSetOrMap(binding);
    String methodName =
        String.format("%sTo%s", binding.implementationSimpleName, binding.interfaceSimpleName);

    return String.format(
        "@dagger.Binds\n%s%s %s(%s %s);",
        intoSetOrMap,
        binding.interfaceFullName,
        methodName,
        binding.implementationFullName,
        binding.implementationSimpleName);
  }

  private String compileSingleBindingForWrapperComponent(SingleBinding binding) {
    String methodName =
        binding.interfaceSimpleName.substring(0, 1).toLowerCase()
            + binding.interfaceSimpleName.substring(1);

    return String.format("%s %s();", binding.interfaceFullName, methodName);
  }

  // TODO: scope
  private String compileSingleBindingForWrapperModule(SingleBinding binding, String componentName) {
    String methodName =
        binding.interfaceSimpleName.substring(0, 1).toLowerCase()
            + binding.interfaceSimpleName.substring(1);

    return String.format(
        "@javax.inject.Singleton\n@dagger.Provides\nstatic %s %s(%s component) {\n\treturn component.%s();\n}",
        binding.interfaceFullName, methodName, componentName, methodName);
  }

  private String compileMultiBinding(MultiBinding binding) {
    String methodName =
        binding.interfaceSimpleName.substring(0, 1).toLowerCase()
            + binding.interfaceSimpleName.substring(1)
            + "Multi";

    switch (binding.multiBind) {
      case STRING_MAP:
        return String.format(
            "@dagger.multibindings.Multibinds\njava.util.Map<String, %s> %s();",
            binding.interfaceFullName, methodName);
      case CLASS_MAP:
        return String.format(
            "@dagger.multibindings.Multibinds\njava.util.Map<Class<?>, %s> %s();",
            binding.interfaceFullName, methodName);
      case SET:
      default:
        return String.format(
            "@dagger.multibindings.Multibinds\njava.util.Set<%s> %s();",
            binding.interfaceFullName, methodName);
    }
  }

  private String compileMultiBindingForWrapperComponent(MultiBinding binding) {
    String methodName =
        binding.interfaceSimpleName.substring(0, 1).toLowerCase()
            + binding.interfaceSimpleName.substring(1);

    return String.format("%s %s();", binding.getInterfaceLazy(), methodName);
  }

  private String compileMultiBindingForWrapperComponent(SingleBinding binding) {
    String methodName =
        binding.interfaceSimpleName.substring(0, 1).toLowerCase()
            + binding.interfaceSimpleName.substring(1);

    switch (binding.multiBind.get()) {
      case STRING_MAP:
        return String.format(
            "java.util.Map<String, %s> %s();", binding.interfaceFullName, methodName);
      case CLASS_MAP:
        return String.format(
            "java.util.Map<Class<?>, %s> %s();", binding.interfaceFullName, methodName);
      case SET:
      default:
        return String.format("java.util.Set<%s> %s();", binding.interfaceFullName, methodName);
    }
  }

  // TODO: maps
  private String compileMultiBindingForEncapsulatedModule(MultiBinding binding) {
    String methodName =
        binding.interfaceSimpleName.substring(0, 1).toLowerCase()
            + binding.interfaceSimpleName.substring(1);
    String externalMultiBindings = "ExternalMultiBindings";

    switch (binding.multiBind) {
      case STRING_MAP:
        throw new IllegalStateException(
            "Encapsulation of Map multibindings is currently not supported.");
        // return String.format("// java.util.Map<String, %s> %s();", binding.interfaceFullName,
        // methodName);
      case CLASS_MAP:
        throw new IllegalStateException(
            "Encapsulation of Map multibindings is currently not supported.");
        // return String.format("// java.util.Map<Class<?>, %s> %s();", binding.interfaceFullName,
        // methodName);
      case SET:
      default:
        return String.format(
            "@javax.inject.Singleton\n"
                + "@dagger.Provides\n"
                + "@dagger.multibindings.ElementsIntoSet\n"
                + "static java.util.Set<%1$s> %2$sExternal(%3$s externalMultiBindings) {\n"
                + "\treturn externalMultiBindings.%2$s()%4$s;\n"
                + "}",
            binding.interfaceFullName,
            methodName,
            externalMultiBindings,
            binding.lazy ? ".get()" : "");
    }
  }

  private String compileExternalMultiBindingsWrapper(List<MultiBinding> bindings, List<Map.Entry<String, String>> injections) {
    String externalMultiBindings = "ExternalMultiBindings";
    return Stream.concat(bindings.stream()
        .map(
            binding -> {
              String methodName =
                  binding.interfaceSimpleName.substring(0, 1).toLowerCase()
                      + binding.interfaceSimpleName.substring(1);
              switch (binding.multiBind) {
                case STRING_MAP:
                  throw new IllegalStateException(
                      "Encapsulation of Map multibindings is currently not supported.");
                  // return String.format("// java.util.Map<String, %s> %s();",
                  // binding.interfaceFullName,
                  // methodName);
                case CLASS_MAP:
                  throw new IllegalStateException(
                      "Encapsulation of Map multibindings is currently not supported.");
                  // return String.format("// java.util.Map<Class<?>, %s> %s();",
                  // binding.interfaceFullName,
                  // methodName);
                case SET:
                default:
                  return String.format("\t%s %s();", binding.getInterfaceLazy(), methodName);
              }
            }),
            injections.stream()
                .map(entry -> String.format("\t%s %s();", entry.getKey(), entry.getValue())))
        .collect(
            Collectors.joining(
                "\n", String.format("interface %s {\n", externalMultiBindings), "\n}"));
  }

  // TODO: maps
  private String compileMultiBindingForWrapperModule(SingleBinding binding, String componentName) {
    String methodName =
        binding.interfaceSimpleName.substring(0, 1).toLowerCase()
            + binding.interfaceSimpleName.substring(1);

    switch (binding.multiBind.get()) {
      case STRING_MAP:
        throw new IllegalStateException(
            "Encapsulation of Map multibindings is currently not supported.");
        // return String.format("// java.util.Map<String, %s> %s();", binding.interfaceFullName,
        // methodName);
      case CLASS_MAP:
        throw new IllegalStateException(
            "Encapsulation of Map multibindings is currently not supported.");
        // return String.format("// java.util.Map<Class<?>, %s> %s();", binding.interfaceFullName,
        // methodName);
      case SET:
      default:
        return String.format(
            "@javax.inject.Singleton\n"
                + "@dagger.Provides\n"
                + "@dagger.multibindings.ElementsIntoSet\n"
                + "static java.util.Set<%1$s> %2$s(%3$s component) {\n"
                + "\treturn component.%2$s();\n"
                + "}",
            binding.interfaceFullName, methodName, componentName);
    }
  }

  private String compileIntoSetOrMap(SingleBinding binding) {
    if (binding.multiBind.isPresent()) {
      // TODO: check for duplicate keys
      switch (binding.multiBind.get()) {
        case SET:
          return "@dagger.multibindings.IntoSet\n";
        case STRING_MAP:
          String stringKey =
              binding
                  .multiBindKey
                  .filter(mapKeyString -> !mapKeyString.isBlank())
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Missing @AutoBind mapKeyString for "
                                  + binding.implementationFullName));
          return String.format(
              "@dagger.multibindings.IntoMap\n@dagger.multibindings.StringKey(\"%s\")\n",
              stringKey);
        case CLASS_MAP:
          String classKey =
              binding
                  .multiBindKey
                  .filter(
                      mapKeyClass -> !Objects.equals(mapKeyClass, Void.class.getCanonicalName()))
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Missing @AutoBind mapKeyClass for "
                                  + binding.implementationFullName));
          return String.format(
              "@dagger.multibindings.IntoMap\n@dagger.multibindings.ClassKey(%s.class)\n",
              classKey);
      }
    }
    return "";
  }
}
