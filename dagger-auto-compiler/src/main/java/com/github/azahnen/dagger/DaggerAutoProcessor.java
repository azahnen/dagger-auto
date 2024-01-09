package com.github.azahnen.dagger;

import com.github.azahnen.dagger.annotations.AutoBind;
import com.github.azahnen.dagger.annotations.AutoModule;
import com.github.azahnen.dagger.annotations.AutoMultiBind;
import com.google.auto.service.AutoService;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@SupportedSourceVersion(SourceVersion.RELEASE_11)
@AutoService(Processor.class)
public class DaggerAutoProcessor extends AbstractProcessor {

    private static final Set<Class<? extends Annotation>> SUPPORTED_ANNOTATIONS =
            Set.of(AutoBind.class, AutoMultiBind.class, AutoModule.class);

    private final Map<Class<? extends Annotation>, TypeElement> annotationTypes;
    private final Map<String, JavaFileObject> sourceFiles;
    private final DaggerAutoCompiler compiler;
    private DaggerAutoParser parser;

    public DaggerAutoProcessor() {
        this.annotationTypes = new HashMap<>();
        this.sourceFiles = new ConcurrentHashMap<>();
        this.compiler = new DaggerAutoCompiler();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        if (annotationTypes.isEmpty()) {
            SUPPORTED_ANNOTATIONS.forEach(
                    annotation -> annotationTypes.put(annotation, getTypeElement(annotation)));
        }
        this.parser = new DaggerAutoParser(annotationTypes, processingEnv);
    }

    @Override
    public Set<String> getSupportedOptions() {
        return super.getSupportedOptions();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return SUPPORTED_ANNOTATIONS.stream().map(Class::getCanonicalName).collect(Collectors.toSet());
    }

    @Override
    public boolean process(
            Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        List<Module> modules = parser.parse(annotations, roundEnvironment);

        Map<String, String> files = compiler.compile(modules);

        files.forEach(
                (name, content) -> {
                    try {
                        if (!sourceFiles.containsKey(name)) {
                            JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(name);
                            sourceFiles.put(name, sourceFile);
                        }

                        JavaFileObject sourceFile = sourceFiles.get(name);

                        try (Writer writer = sourceFile.openWriter()) {
                            writer.write(content);
                        }
                    } catch (IOException e) {
                        processingEnv.getMessager().printMessage(Kind.ERROR, e.getMessage());
                    }
                });

        return true;
    }

    private TypeElement getTypeElement(Class<?> clazz) {
        return processingEnv.getElementUtils().getTypeElement(clazz.getCanonicalName());
    }
}
