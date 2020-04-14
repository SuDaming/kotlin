/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine.util

import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.debugger.ui.impl.watch.MethodsTracker
import com.intellij.debugger.ui.impl.watch.StackFrameDescriptorImpl
import com.sun.jdi.ObjectReference
import org.jetbrains.kotlin.idea.debugger.coroutine.data.*
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.ContinuationHolder
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.CoroutinePreflightStackFrame
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.LocationStackFrameProxyImpl
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.SuspendExitMode
import org.jetbrains.kotlin.idea.debugger.safeLineNumber
import org.jetbrains.kotlin.idea.debugger.safeLocation
import org.jetbrains.kotlin.idea.debugger.safeMethod


class CoroutineFrameBuilder {

    companion object {
        fun build(coroutine: CoroutineInfoData, suspendContext: SuspendContextImpl): List<CoroutineStackFrameItem> =
            when {
                coroutine.isRunning() -> buildStackFrameForActive(coroutine, suspendContext)
                coroutine.isSuspended() -> coroutine.stackTrace
                else -> emptyList()
            }

        private fun buildStackFrameForActive(coroutine: CoroutineInfoData, suspendContext: SuspendContextImpl): List<CoroutineStackFrameItem> {
            coroutine.activeThread ?: return emptyList()

            val coroutineStackFrameList = mutableListOf<CoroutineStackFrameItem>()
            val threadReferenceProxyImpl = ThreadReferenceProxyImpl(suspendContext.debugProcess.virtualMachineProxy, coroutine.activeThread)
            val realFrames = threadReferenceProxyImpl.forceFrames()
            for (runningStackFrameProxy in realFrames) {
                if (runningStackFrameProxy.location().isPreFlight()) {
                    val preflightStackFrame = lookupContinuation(suspendContext, runningStackFrameProxy)
                        ?: continue
                    coroutineStackFrameList.add(RunningCoroutineStackFrameItem(preflightStackFrame.stackFrameProxy))
                    // clue coroutine stack into the thread's real stack
                    val stackFrameItems = preflightStackFrame.coroutineInfoData.stackTrace.map {
                        RestoredCoroutineStackFrameItem(runningStackFrameProxy, it.location, it.spilledVariables)
                    }
                    coroutineStackFrameList.addAll(stackFrameItems)
                } else
                    coroutineStackFrameList.add(RunningCoroutineStackFrameItem(runningStackFrameProxy))
            }
            return coroutineStackFrameList
        }

        fun build(preflightFrame: CoroutinePreflightStackFrame, suspendContext: SuspendContextImpl): List<CoroutineStackFrameItem> {
            val stackFrames = mutableListOf<CoroutineStackFrameItem>()
            stackFrames.addAll(preflightFrame.coroutineInfoData.stackTrace)

            val lastRestoredFrame = preflightFrame.coroutineInfoData.stackTrace.first()

            stackFrames.addAll(preflightFrame.threadPreCoroutineFrames.mapIndexedNotNull { index, stackFrameProxyImpl ->
                if (index == 0)
                    PreCoroutineStackFrameItem(stackFrameProxyImpl, lastRestoredFrame) // get location and variables also from restored part
                else
                    suspendContext.invokeInManagerThread { PreCoroutineStackFrameItem(stackFrameProxyImpl) }
            })
            stackFrames.addAll(preflightFrame.coroutineInfoData.creationStackTrace)
            return stackFrames
        }

        fun coroutineExitFrame(
            frame: StackFrameProxyImpl,
            suspendContext: SuspendContextImpl
        ): CoroutinePreflightStackFrame? {
            return suspendContext.invokeInManagerThread {
                if (frame.location().isPreFlight() || frame.location().isPreExitFrame()) {
                    if (coroutineDebuggerTraceEnabled())
                        ContinuationHolder.log.debug("Entry frame found: ${frame.format()}")
                    lookupContinuation(suspendContext, frame)
                } else
                    null
            }
        }

        private fun filterNegativeLineNumberInvokeSuspendFrames(frame: StackFrameProxyImpl): Boolean {
            val method = frame.safeLocation()?.safeMethod() ?: return false
            return method.isInvokeSuspend() && frame.safeLocation()?.safeLineNumber() ?: 0 < 0
        }

        fun lookupContinuation(
            suspendContext: SuspendContextImpl,
            frame: StackFrameProxyImpl
        ): CoroutinePreflightStackFrame? {
            if (threadAndContextSupportsEvaluation(suspendContext, frame)) {

                val method = frame.safeLocation()?.safeMethod() ?: return null
                val mode = when {
                    method.isSuspendLambda() -> SuspendExitMode.SUSPEND_LAMBDA
                    method.isSuspendMethod() -> SuspendExitMode.SUSPEND_METHOD
                    else -> return null
                }

                val context = suspendContext.executionContext() ?: return null
                val continuation = when(mode) {
                    SuspendExitMode.SUSPEND_LAMBDA -> getThisContinuation(frame)
                    SuspendExitMode.SUSPEND_METHOD -> getLVTContinuation(frame)
                    else -> null
                } ?: return null

                val framesLeft = leftThreadStack(frame) ?: return null
                val coroutineInfo = ContinuationHolder(context).extractCoroutineInfoData(continuation, context) ?: return null
                return preflight(frame, coroutineInfo, framesLeft, mode)
            }
            return null
        }

        private fun preflight(
            frame: StackFrameProxyImpl,
            coroutineInfoData: CoroutineInfoData,
            originalFrames: List<StackFrameProxyImpl>,
            mode: SuspendExitMode
        ): CoroutinePreflightStackFrame? {
            val descriptor =
                coroutineInfoData.topRestoredFrame()?.let {
                    StackFrameDescriptorImpl(LocationStackFrameProxyImpl(it.location, frame), MethodsTracker())
                }
                    ?: StackFrameDescriptorImpl(frame, MethodsTracker())
            return CoroutinePreflightStackFrame(
                coroutineInfoData,
                descriptor,
                originalFrames,
                mode
            )
        }


        private fun getLVTContinuation(frame: StackFrameProxyImpl?) =
            frame?.continuationVariableValue()

        private fun getThisContinuation(frame: StackFrameProxyImpl?): ObjectReference? =
            frame?.thisVariableValue()


        fun buildCreationFrames(coroutine: CoroutineInfoData) =
            coroutine.creationStackTrace

        fun leftThreadStack(frame: StackFrameProxyImpl): List<StackFrameProxyImpl>? {
            var frames = frame.threadProxy().frames()
            val indexOfCurrentFrame = frames.indexOf(frame)
            if (indexOfCurrentFrame >= 0) {
                val indexofGetCoroutineSuspended =
                    hasGetCoroutineSuspended(frames)
                // @TODO if found - skip this thread stack
                if (indexofGetCoroutineSuspended >= 0)
                    return null
                return frames.drop(indexOfCurrentFrame)
            } else
                return null
        }
    }
}