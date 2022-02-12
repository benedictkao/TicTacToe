package com.benkao.processor

import com.benkao.annotations.CreateToDestroy
import com.benkao.annotations.InitToClear
import com.benkao.annotations.LifecycleViewModel
import com.benkao.annotations.StartToStop
import com.google.auto.service.AutoService
import com.squareup.kotlinpoet.*
import io.reactivex.rxjava3.core.Completable
import java.io.File
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic

@AutoService(Processor::class)
class Generator: AbstractProcessor() {
    companion object {
        const val KAPT_KOTLIN_GENERATED_OPTION_NAME = "kapt.kotlin.generated"
        const val VIEWMODEL_OBJECT_NAME = "viewModel"
        const val STREAMS_HANDLER_CLASS_SUFFIX = "_StreamsInitializer"
        const val STREAMS_HANDLER_BIND_METHOD_NAME = "start"

        const val ERROR_DIRECTORY_GENERATION = "Could not generate directory"

        const val STREAMS_MODEL_PACKAGE = "com.benkao.tictactoe.ui.base"
        const val STREAMS_MODEL_NAME = "LifecycleStreams"
    }

    private var kaptKotlinGeneratedDir: String? = null

    override fun getSupportedAnnotationTypes(): MutableSet<String> {
        return mutableSetOf(
            LifecycleViewModel::class.java.name,
            InitToClear::class.java.name,
            CreateToDestroy::class.java.name,
            StartToStop::class.java.name
        )
    }

    override fun getSupportedSourceVersion(): SourceVersion {
        return SourceVersion.latestSupported()
    }

    override fun process(
        annotations: MutableSet<out TypeElement>?,
        roundEnv: RoundEnvironment
    ): Boolean {
        kaptKotlinGeneratedDir = processingEnv.options[KAPT_KOTLIN_GENERATED_OPTION_NAME]

        roundEnv.getElementsAnnotatedWith(LifecycleViewModel::class.java)
            .forEach {
                if (it.kind != ElementKind.CLASS) {
                    printErrorMessage("Only classes can be annotated with @LifecycleViewModel")
                    return true
                }
                processLifecycleViewModel(it)
            }
        return false
    }

    private fun processLifecycleViewModel(vmElement: Element) {
        val className = vmElement.simpleName.toString()
        val pack = processingEnv.elementUtils.getPackageOf(vmElement).toString()

        val fileName = "$className$STREAMS_HANDLER_CLASS_SUFFIX"
        val fileBuilder = FileSpec.builder(pack, fileName)

        val stringBuilders = Array(3) { StringBuilder() }

        vmElement.enclosedElements.forEach { enclosed ->

            enclosed.getAnnotation(InitToClear::class.java)
                ?.takeIf { isCompletableMethod(enclosed) }
                ?.let {
                    stringBuilders[0].append(
                        "$VIEWMODEL_OBJECT_NAME.${enclosed.simpleName}(),"
                    )
                }

            enclosed.getAnnotation(CreateToDestroy::class.java)
                ?.takeIf { isCompletableMethod(enclosed) }
                ?.let {
                    stringBuilders[1].append(
                        "$VIEWMODEL_OBJECT_NAME.${enclosed.simpleName}(),"
                    )
                }

            enclosed.getAnnotation(StartToStop::class.java)
                ?.takeIf { isCompletableMethod(enclosed) }
                ?.let {
                    stringBuilders[2].append(
                        "$VIEWMODEL_OBJECT_NAME.${enclosed.simpleName}(),"
                    )
                }
        }

        val vm = ParameterSpec.builder(
            VIEWMODEL_OBJECT_NAME,
            vmElement.asType().asTypeName()
        ).build()

        val classBuilder = TypeSpec.objectBuilder(fileName)
            .addFunction(FunSpec.builder(STREAMS_HANDLER_BIND_METHOD_NAME)
                .returns(ClassName(STREAMS_MODEL_PACKAGE, STREAMS_MODEL_NAME))
                .addParameter(vm)
                .addStatement("$VIEWMODEL_OBJECT_NAME.subscribeUntilClear(listOf(${stringBuilders[0]}))")
                .addStatement("return $STREAMS_MODEL_NAME(listOf(${stringBuilders[1]}),listOf(${stringBuilders[2]}))")
                .build())

        val file = fileBuilder.addType(classBuilder.build()).build()
        val kaptKotlinGeneratedDir = processingEnv.options[KAPT_KOTLIN_GENERATED_OPTION_NAME]
        kaptKotlinGeneratedDir?.let { file.writeTo(File(it)) }
            ?: printErrorMessage(ERROR_DIRECTORY_GENERATION)
    }

    private fun isCompletableMethod(element: Element): Boolean =
        element.kind == ElementKind.METHOD &&
                processingEnv.typeUtils.isSameType(
                    (element as ExecutableElement).returnType,
                    processingEnv.elementUtils
                        .getTypeElement(Completable::class.qualifiedName)
                        .asType()
                )

    private fun printErrorMessage(msg: String) {
        processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, msg)
    }
}