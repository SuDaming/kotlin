/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.serialization.deserialization.ClassData

interface ModuleAnnotationsResolver {
    fun getAnnotationsOnContainingModule(descriptor: DeclarationDescriptor): List<ClassId>

    fun getAllOptionalAnnotationClasses(): List<ClassData>

    companion object {
        fun getInstance(project: Project): ModuleAnnotationsResolver =
            ServiceManager.getService(project, ModuleAnnotationsResolver::class.java)
    }
}
