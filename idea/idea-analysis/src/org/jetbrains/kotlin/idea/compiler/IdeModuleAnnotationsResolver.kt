/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.compiler

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.IDEPackagePartProvider
import org.jetbrains.kotlin.js.resolve.getAnnotationsOnContainingJsModule
import org.jetbrains.kotlin.load.kotlin.getJvmModuleNameForDeserializedDescriptor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.resolve.ModuleAnnotationsResolver
import org.jetbrains.kotlin.serialization.deserialization.ClassData

class IdeModuleAnnotationsResolver(project: Project) : ModuleAnnotationsResolver {
    // TODO: allScope is incorrect here, need to look only in the root where the element comes from
    private val packagePartProvider = IDEPackagePartProvider(GlobalSearchScope.allScope(project))

    override fun getAnnotationsOnContainingModule(descriptor: DeclarationDescriptor): List<ClassId> {
        getAnnotationsOnContainingJsModule(descriptor)?.let { return it }

        val moduleName = getJvmModuleNameForDeserializedDescriptor(descriptor) ?: return emptyList()
        return packagePartProvider.getAnnotationsOnBinaryModule(moduleName)
    }

    // Optional annotations are not needed in IDE because they can only be used in common module sources, and they are loaded via the
    // standard common module resolution there. (In the CLI compiler the situation is different because we compile common+platform
    // sources together, _without_ common dependencies.)
    override fun getAllOptionalAnnotationClasses(): List<ClassData> =
        emptyList()
}
