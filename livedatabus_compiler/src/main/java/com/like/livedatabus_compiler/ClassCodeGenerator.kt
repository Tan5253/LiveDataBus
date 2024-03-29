package com.like.livedatabus_compiler

import com.like.livedatabus_annotations.BusObserver
import com.squareup.javapoet.*
import java.io.IOException
import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement

/*
public class MainViewModel_Proxy extends Bridge {
    @Override
    protected void autoGenerate(@NotNull Object host, @NotNull LifecycleOwner owner) {
        observe(host, owner, tag, requestCode, isSticky, new Observer<String>() {
            @Override
            public void onChanged(@Nullable String s) {
                // 调用@BusObserver注解的接收数据的方法
                ((MainViewModel) host).method(s);
            }
        });
    }
}
 */
class ClassCodeGenerator {
    companion object {
        private const val CLASS_UNIFORM_MARK = "_Proxy"
        // 因为java工程中没有下面这些类(Android中的类)，所以只能采用ClassName的方式。
        private val BRIDGE = ClassName.get("com.like.livedatabus", "Bridge")
        private val OBSERVER = ClassName.get("android.arch.lifecycle", "Observer")
        private val LIFECYCLE_OWNER = ClassName.get("android.arch.lifecycle", "LifecycleOwner")
        private val OBJECT = ClassName.get("java.lang", "Object")
        private val NO_OBSERVER_PARAMS = ClassName.get("com.like.livedatabus", "NoObserverParams")
    }

    private var mPackageName = ""// 生成的类的包名
    private var mHostClassName: ClassName? = null// 宿主的类名
    private val mMethodInfoList = mutableSetOf<MethodInfo>()// 类中的所有方法

    fun create() {
        if (mMethodInfoList.isEmpty() || mPackageName.isEmpty() || mHostClassName == null) {
            return
        }
        // 创建包名及类的注释
        val javaFile = JavaFile.builder(mPackageName, createClass())
                .addFileComment(" This codes are generated automatically by LiveDataBus. Do not modify!")// 类的注释
                .build()

        try {
            javaFile.writeTo(ProcessUtils.filer)
        } catch (e: IOException) {
            e.printStackTrace()
        }

    }

    /**
     * 创建类
     *
     * public class MainViewModel_Proxy extends Bridge {}
     */
    private fun createClass(): TypeSpec =
            TypeSpec.classBuilder(mHostClassName?.simpleName() + CLASS_UNIFORM_MARK)
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                    .superclass(BRIDGE)
                    .addMethod(createMethod())
                    .build()

    /**
     * 创建autoGenerate方法
     *
     * @Override
     * protected void autoGenerate(@NotNull Object host, @NotNull LifecycleOwner owner) {}
     */
    private fun createMethod(): MethodSpec {
        val builder = MethodSpec.methodBuilder("autoGenerate")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(OBJECT, "host", Modifier.FINAL)
                .addParameter(LIFECYCLE_OWNER, "owner", Modifier.FINAL)
                .addAnnotation(Override::class.java)
        for (binder in mMethodInfoList) {
            builder.addCode(createMethodCodeBlock(binder))
        }
        return builder.build()
    }

    /**
     * 创建autoGenerate方法中调用的方法
     *
     * observe(host, owner, tag, requestCode, isSticky, observer)
     */
    private fun createMethodCodeBlock(methodInfo: MethodInfo): CodeBlock {
        val builder = CodeBlock.builder()
        methodInfo.tag?.forEach {
            val requestCode = methodInfo.requestCode
            val isSticky = methodInfo.isSticky

            val codeBlockBuilder = CodeBlock.builder()
            codeBlockBuilder.addStatement("observe(host\n,owner\n,\$S\n,\$S\n,\$L\n,\$L)", it, requestCode, isSticky, createObserverParam(methodInfo))
            builder.add(codeBlockBuilder.build())
        }
        return builder.build()
    }

    /*
     * 创建observe方法的第四个参数observer，是一个匿名内部类。
     *
        new Observer<String>() {
            @Override
            public void onChanged(@Nullable String s) {
                // 调用@BusObserver注解的接收数据的方法
                ((MainViewModel) host).method(s);
            }
        }
     */
    private fun createObserverParam(methodInfo: MethodInfo): TypeSpec {
        // 获取onChanged方法的参数类型
        var typeName: TypeName = NO_OBSERVER_PARAMS
        methodInfo.paramType?.let {
            if (it.kind.isPrimitive) {
                typeName = TypeName.get(it)
                if (!typeName.isBoxedPrimitive)// 如果是装箱数据类型
                    typeName = typeName.box()
            } else
                typeName = ClassName.get(it)
        }

        // 创建onChanged方法
        val methodBuilder = MethodSpec.methodBuilder("onChanged")
                .addAnnotation(Override::class.java)
                .addModifiers(Modifier.PUBLIC)
        methodBuilder.addParameter(typeName, "t")
        // 如果typeName为NO_OBSERVER_PARAMS，则说明被@BusObserver注解的方法没有参数。
        // ((MainViewModel) host).method(s);
        val callbackStatement = "((${mHostClassName?.simpleName()}) host).${methodInfo.methodName}(${if (typeName == NO_OBSERVER_PARAMS) "" else "t"});"
        methodBuilder.addStatement(
                // 当参数为NO_OBSERVER_PARAMS时，代表被@BusObserver注解的方法没有参数。
                if (typeName == NO_OBSERVER_PARAMS) {
                    // 为了和其它参数（可为null）区分开，需要判断null
                    "if (t != null) {$callbackStatement}"
                } else {
                    callbackStatement
                }
        )
        // 创建匿名内部类
        return TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(ParameterizedTypeName.get(OBSERVER, typeName))
                .addMethod(methodBuilder.build())
                .build()
    }

    /**
     * 添加元素，用于生成类
     */
    fun addElement(element: Element) {
        if (mHostClassName == null) {
            mHostClassName = ClassName.get(element.enclosingElement as TypeElement)// getEnclosingElement()所在类的对象信息
            mPackageName = mHostClassName!!.packageName()
        }

        val busObserverAnnotationClass = BusObserver::class.java
        val methodInfo = MethodInfo()
        methodInfo.tag = element.getAnnotation(busObserverAnnotationClass).value
        methodInfo.requestCode = element.getAnnotation(busObserverAnnotationClass).requestCode

        // 判断是否有重复的tag + requestCode
        if (methodInfo.tag == null) return
        val isRepeat = mMethodInfoList.any {
            it.tag?.intersect(methodInfo.tag!!.toList())?.isNotEmpty() ?: false &&
                    it.requestCode == methodInfo.requestCode
        }
        if (isRepeat) return

        methodInfo.methodName = element.simpleName.toString()
        methodInfo.isSticky = element.getAnnotation(busObserverAnnotationClass).isSticky

        val executableElement = element as ExecutableElement
        if (executableElement.parameters.size == 1) {
            val ve = executableElement.parameters[0]
            methodInfo.paramType = ve.asType()
        }
        mMethodInfoList.add(methodInfo)
    }
}