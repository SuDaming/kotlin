/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine.proxy

import com.intellij.debugger.engine.JavaValue
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.jdi.GeneratedLocation
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.ui.impl.watch.MethodsTracker
import com.intellij.debugger.ui.impl.watch.StackFrameDescriptorImpl
import com.intellij.xdebugger.frame.XStackFrame
import com.sun.jdi.*
import org.jetbrains.kotlin.idea.debugger.*
import org.jetbrains.kotlin.idea.debugger.coroutine.data.*
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.mirror.DebugMetadata
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.mirror.FieldVariable
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.mirror.MirrorOfBaseContinuationImpl
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.mirror.MirrorOfStackFrame
import org.jetbrains.kotlin.idea.debugger.coroutine.util.*
import org.jetbrains.kotlin.idea.debugger.evaluate.DefaultExecutionContext

class ContinuationHolder(context: DefaultExecutionContext) {
    private val debugMetadata: DebugMetadata? = DebugMetadata.instance(context)
    private val log by logger

    fun extractCoroutineInfoData(continuation: ObjectReference, context: DefaultExecutionContext): CoroutineInfoData? {
        try {
            val consumer = mutableListOf<CoroutineStackFrameItem>()
            val continuationStack = debugMetadata?.fetchContinuationStack(continuation, context) ?: return null
            for (frame in continuationStack.coroutineStack) {
                val coroutineStackFrame = createLocation(frame, context)
                if (coroutineStackFrame != null)
                    consumer.add(coroutineStackFrame)
            }
            return CoroutineInfoData.lookup(continuation, context, consumer)
        } catch (e: Exception) {
            log.error("Error while looking for stack frame.", e)
        }
        return null
    }

    companion object {
        val log by logger

        private fun createLocation(
            frame: MirrorOfStackFrame,
            context: DefaultExecutionContext
        ): DefaultCoroutineStackFrameItem? {
            val stackTraceElement = frame.baseContinuationImpl.stackTraceElement?.stackTraceElement() ?: return null
            val locationClass = context.findClassSafe(stackTraceElement.className) ?: return null
            val generatedLocation = GeneratedLocation(context.debugProcess, locationClass, stackTraceElement.methodName, stackTraceElement.lineNumber)
            val spilledVariables = frame.baseContinuationImpl.spilledValues(context)
            return DefaultCoroutineStackFrameItem(generatedLocation, spilledVariables)
        }
    }
}

fun MirrorOfBaseContinuationImpl.spilledValues(context: DefaultExecutionContext): List<JavaValue> {
    return fieldVariables.mapNotNull {
        it.toJavaValue(that, context)
    }
}

fun FieldVariable.toJavaValue(continuation: ObjectReference, context: DefaultExecutionContext): JavaValue {
    val valueDescriptor = ContinuationValueDescriptorImpl(
        context.project,
        continuation,
        fieldName,
        variableName
    )
    return JavaValue.create(
        null,
        valueDescriptor,
        context.evaluationContext,
        context.debugProcess.xdebugProcess!!.nodeManager,
        false
    )

}
