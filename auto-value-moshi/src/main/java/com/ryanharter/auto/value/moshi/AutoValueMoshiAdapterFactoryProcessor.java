package com.ryanharter.auto.value.moshi;

import com.google.auto.service.AutoService;
import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.AutoValueExtension;
import com.google.common.collect.ImmutableSet;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import static com.ryanharter.auto.value.moshi.AutoValueMoshiExtension.ADAPTER_CLASS_NAME;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.tools.Diagnostic.Kind.ERROR;

/**
 * Annotation Processor responsible for the generation of the {@link JsonAdapter.Factory} class
 * annotated with {@link MoshiAdapterFactory}.
 *
 * @author rharter
 */
@AutoService(Processor.class)
public class AutoValueMoshiAdapterFactoryProcessor extends AbstractProcessor {
  private static final ParameterSpec TYPE_SPEC = ParameterSpec.builder(Type.class, "type").build();
  private static final WildcardTypeName WILDCARD_TYPE_NAME =
      WildcardTypeName.subtypeOf(Annotation.class);
  private static final ParameterSpec ANNOTATIONS_SPEC = ParameterSpec
      .builder(
          ParameterizedTypeName.get(ClassName.get(Set.class), WILDCARD_TYPE_NAME), "annotations")
      .build();
  private static final ParameterSpec MOSHI_SPEC =
      ParameterSpec.builder(Moshi.class, "moshi").build();
  private static final ParameterizedTypeName FACTORY_RETURN_TYPE_NAME =
      ParameterizedTypeName.get(ADAPTER_CLASS_NAME, WildcardTypeName.subtypeOf(TypeName.OBJECT));

  private final AutoValueMoshiExtension extension = new AutoValueMoshiExtension();
  private Types typeUtils;
  private Elements elementUtils;

  @Override public Set<String> getSupportedAnnotationTypes() {
    return ImmutableSet.of(AutoValue.class.getName(), MoshiAdapterFactory.class.getName());
  }

  @Override public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    typeUtils = processingEnv.getTypeUtils();
    elementUtils = processingEnv.getElementUtils();
  }

  @Override public boolean process(Set<? extends TypeElement> annotations,
      RoundEnvironment roundEnv) {
    List<Element> elements = new LinkedList<>();
    for (Element element : roundEnv.getElementsAnnotatedWith(AutoValue.class)) {
      AutoValueExtension.Context context = new LimitedContext(processingEnv, (TypeElement) element);
      if (extension.applicable(context)) {
        elements.add(element);
      }
    }

    if (!elements.isEmpty()) {
      Set<? extends Element> adaptorFactories =
          roundEnv.getElementsAnnotatedWith(MoshiAdapterFactory.class);
      for (Element element : adaptorFactories) {
        if (!element.getModifiers().contains(ABSTRACT)) {
          error(element, "Must be abstract!");
        }
        // Safe to cast because this is only applicable on types anyway
        TypeElement type = (TypeElement) element;
        if (!implementsJsonAdapterFactory(type)) {
          error(element, "Must implement JsonAdapter.Factory!");
        }
        String adapterName = classNameOf(type);
        String packageName = packageNameOf(type);

        MoshiAdapterFactory annotation = element.getAnnotation(MoshiAdapterFactory.class);
        boolean requestNullSafeAdapters = annotation.nullSafe();

        TypeSpec jsonAdapterFactory = createJsonAdapterFactory(elements, packageName, adapterName,
            requestNullSafeAdapters);
        JavaFile file = JavaFile.builder(packageName, jsonAdapterFactory).build();
        try {
          file.writeTo(processingEnv.getFiler());
        } catch (IOException e) {
          processingEnv.getMessager()
              .printMessage(ERROR,
                  "Failed to write TypeAdapterFactory: " + e.getLocalizedMessage());
        }
      }
    }

    return false;
  }

  private TypeSpec createJsonAdapterFactory(List<Element> elements, String packageName,
      String factoryName, boolean requestNullSafeAdapters) {
    TypeSpec.Builder factory =
        TypeSpec.classBuilder(ClassName.get(packageName, "AutoValueMoshi_" + factoryName));
    factory.addModifiers(PUBLIC, FINAL);
    factory.superclass(ClassName.get(packageName, factoryName));

    ParameterSpec type = TYPE_SPEC;
    ParameterSpec annotations = ANNOTATIONS_SPEC;
    ParameterSpec moshi = MOSHI_SPEC;

    MethodSpec.Builder create = MethodSpec.methodBuilder("create")
        .addModifiers(PUBLIC)
        .addAnnotation(Override.class)
        .addParameters(ImmutableSet.of(type, annotations, moshi))
        .returns(FACTORY_RETURN_TYPE_NAME);

    CodeBlock.Builder classes = null;
    CodeBlock.Builder generics = null;

    int numGenerics = 0;
    int numClasses = 0;

    // Avoid providing an adapter for an annotated type.
    create.addStatement("if (!$N.isEmpty()) return null", annotations);

    for (int i = 0; i < elements.size(); i++) {
      Element element = elements.get(i);
      TypeName elementTypeName = TypeName.get(element.asType());

      if (elementTypeName instanceof ParameterizedTypeName) {
        if (generics == null) {
          generics = CodeBlock.builder()
              .beginControlFlow("if ($N instanceof $T)", type, ParameterizedType.class)
              .addStatement("$T rawType = (($T) $N).getRawType()", Type.class,
                  ParameterizedType.class, type);
        }

        addControlFlowGeneric(generics, elementTypeName, element, numGenerics,
            requestNullSafeAdapters);
        numGenerics++;
      } else {
        if (classes == null) {
          classes = CodeBlock.builder();
        }

        addControlFlow(classes, CodeBlock.of("$N", type), elementTypeName, numClasses);
        numClasses++;

        String returnStatement = requestNullSafeAdapters
            ? "return $T.$L($N).nullSafe()"
            : "return $T.$L($N)";
        ExecutableElement jsonAdapterMethod = getJsonAdapterMethod(element);
        classes.addStatement(returnStatement, element, jsonAdapterMethod.getSimpleName(), moshi);
      }
    }

    if (generics != null) {
      generics.endControlFlow();
      generics.addStatement("return null");
      generics.endControlFlow();
      create.addCode(generics.build());
    }

    if (classes != null) {
      classes.endControlFlow();
      create.addCode(classes.build());
    }

    create.addStatement("return null");
    factory.addMethod(create.build());
    return factory.build();
  }

  private void addControlFlowGeneric(CodeBlock.Builder block, TypeName elementTypeName,
      Element element, int numGenerics, boolean requestNullSafeAdapters) {
    TypeName typeName = ((ParameterizedTypeName) elementTypeName).rawType;
    CodeBlock typeBlock = CodeBlock.of("rawType");

    addControlFlow(block, typeBlock, typeName, numGenerics);

    String returnStatement = requestNullSafeAdapters
        ? "return $L.$L($N, (($T) $N).getActualTypeArguments()).nullSafe()"
        : "return $L.$L($N, (($T) $N).getActualTypeArguments())";

    ExecutableElement jsonAdapterMethod = getJsonAdapterMethod(element);
    if (jsonAdapterMethod.getParameters().size() > 1) {
      block.addStatement(returnStatement,
          element.getSimpleName(), jsonAdapterMethod.getSimpleName(), MOSHI_SPEC,
          ParameterizedType.class, TYPE_SPEC);
    }
  }

  private void addControlFlow(CodeBlock.Builder block, CodeBlock typeBlock,
      TypeName elementTypeName, int pos) {
    if (pos == 0) {
      block.beginControlFlow("if ($L.equals($T.class))", typeBlock, elementTypeName);
    } else {
      block.nextControlFlow("else if ($L.equals($T.class))", typeBlock, elementTypeName);
    }
  }

  private ExecutableElement getJsonAdapterMethod(Element element) {
    ParameterizedTypeName jsonAdapterType = ParameterizedTypeName.get(
        ClassName.get(JsonAdapter.class), TypeName.get(element.asType()));
    for (ExecutableElement method : ElementFilter.methodsIn(element.getEnclosedElements())) {
      if (method.getModifiers().contains(Modifier.STATIC)
          && method.getModifiers().contains(Modifier.PUBLIC)) {
        TypeMirror rType = method.getReturnType();
        TypeName returnType = TypeName.get(rType);
        if (returnType.equals(jsonAdapterType)) {
          return method;
        }
      }
    }
    throw new AssertionError();
  }

  private void error(Element element, String message, Object... args) {
    if (args.length > 0) {
      message = String.format(message, args);
    }
    processingEnv.getMessager().printMessage(ERROR, message, element);
  }

  private boolean implementsJsonAdapterFactory(TypeElement type) {
    TypeMirror jsonAdapterType
        = elementUtils.getTypeElement(JsonAdapter.Factory.class.getCanonicalName()).asType();
    TypeMirror typeMirror = type.asType();
    if (!type.getInterfaces().isEmpty() || typeMirror.getKind() != TypeKind.NONE) {
      while (typeMirror.getKind() != TypeKind.NONE) {
        if (searchInterfacesAncestry(typeMirror, jsonAdapterType)) {
          return true;
        }
        type = (TypeElement) typeUtils.asElement(typeMirror);
        typeMirror = type.getSuperclass();
      }
    }
    return false;
  }

  private boolean searchInterfacesAncestry(TypeMirror rootIface, TypeMirror target) {
    TypeElement rootIfaceElement = (TypeElement) typeUtils.asElement(rootIface);
    // check if it implements valid interfaces
    for (TypeMirror iface : rootIfaceElement.getInterfaces()) {
      TypeElement ifaceElement = (TypeElement) typeUtils.asElement(rootIface);
      while (iface.getKind() != TypeKind.NONE) {
        if (typeUtils.isSameType(iface, target)) {
          return true;
        }
        // go up
        if (searchInterfacesAncestry(iface, target)) {
          return true;
        }
        // then move on
        iface = ifaceElement.getSuperclass();
      }
    }
    return false;
  }

  /**
   * Returns the name of the given type, including any enclosing types but not the package.
   */
  private static String classNameOf(TypeElement type) {
    String name = type.getQualifiedName().toString();
    String pkgName = packageNameOf(type);
    return pkgName.isEmpty() ? name : name.substring(pkgName.length() + 1);
  }

  /**
   * Returns the name of the package that the given type is in. If the type is in the default
   * (unnamed) package then the name is the empty string.
   */
  private static String packageNameOf(TypeElement type) {
    while (true) {
      Element enclosing = type.getEnclosingElement();
      if (enclosing instanceof PackageElement) {
        return ((PackageElement) enclosing).getQualifiedName().toString();
      }
      type = (TypeElement) enclosing;
    }
  }

  private static class LimitedContext implements AutoValueExtension.Context {
    private final ProcessingEnvironment processingEnvironment;
    private final TypeElement autoValueClass;

    LimitedContext(ProcessingEnvironment processingEnvironment, TypeElement autoValueClass) {
      this.processingEnvironment = processingEnvironment;
      this.autoValueClass = autoValueClass;
    }

    @Override public ProcessingEnvironment processingEnvironment() {
      return processingEnvironment;
    }

    @Override public String packageName() {
      String fqn = autoValueClass.getQualifiedName().toString();
      return fqn.substring(0, fqn.lastIndexOf('.'));
    }

    @Override public TypeElement autoValueClass() {
      return autoValueClass;
    }

    @Override public Map<String, ExecutableElement> properties() {
      return null;
    }

    @Override public Set<ExecutableElement> abstractMethods() {
      return null;
    }
  }
}
