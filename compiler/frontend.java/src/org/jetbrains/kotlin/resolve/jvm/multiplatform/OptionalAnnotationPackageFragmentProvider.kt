/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.jvm.multiplatform

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.PackageFragmentDescriptorImpl
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.CompilerDeserializationConfiguration
import org.jetbrains.kotlin.resolve.ModuleAnnotationsResolver
import org.jetbrains.kotlin.resolve.sam.SamConversionResolver
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.resolve.scopes.MemberScopeImpl
import org.jetbrains.kotlin.serialization.deserialization.*
import org.jetbrains.kotlin.serialization.deserialization.builtins.BuiltInSerializerProtocol
import org.jetbrains.kotlin.storage.NotNullLazyValue
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.utils.Printer

class OptionalAnnotationPackageFragmentProvider(
    project: Project,
    module: ModuleDescriptor,
    storageManager: StorageManager,
    notFoundClasses: NotFoundClasses,
    languageVersionSettings: LanguageVersionSettings,
) : PackageFragmentProvider {
    val packages: MutableMap<FqName, PackageFragmentDescriptor>?

    init {
        val optionalAnnotationClasses = ModuleAnnotationsResolver.getInstance(project).getAllOptionalAnnotationClasses()
        val classDataFinder = OptionalAnnotationClassDataFinder(optionalAnnotationClasses)

        packages =
            if (optionalAnnotationClasses.isEmpty()) null
            else mutableMapOf<FqName, PackageFragmentDescriptor>().also { packages ->
                // This means that we'll try loading annotations as if this was builtins metadata, and we won't find any (since we don't
                // use BuiltInSerializerProtocol when serializing). This is fine but can be improved if needed.
                val serializerProtocol = BuiltInSerializerProtocol
                val components = storageManager.createLazyValue {
                    DeserializationComponents(
                        storageManager, module, CompilerDeserializationConfiguration(languageVersionSettings),
                        classDataFinder,
                        AnnotationAndConstantLoaderImpl(module, notFoundClasses, serializerProtocol),
                        this,
                        LocalClassifierTypeSettings.Default,
                        ErrorReporter.DO_NOTHING,
                        LookupTracker.DO_NOTHING,
                        FlexibleTypeDeserializer.ThrowException,
                        emptyList(),
                        notFoundClasses,
                        ContractDeserializer.DEFAULT,
                        extensionRegistryLite = serializerProtocol.extensionRegistry,
                        samConversionResolver = SamConversionResolver.Empty
                    )
                }

                for ((packageFqName, classes) in classDataFinder.classIdToData.entries.groupBy { it.key.packageFqName }) {
                    val classNames = classes.mapNotNull { (classId) ->
                        classId.shortClassName.takeUnless { classId.isNestedClass }
                    }.toSet()
                    val classDescriptors = storageManager.createLazyValue {
                        classes.mapNotNull { (classId, classData) ->
                            components().classDeserializer.deserializeClass(classId, classData)
                        }.associateBy(ClassDescriptor::getName)
                    }
                    packages[packageFqName] = PackageFragmentForOptionalAnnotations(module, packageFqName, classNames, classDescriptors)
                }
            }
    }

    override fun getPackageFragments(fqName: FqName): List<PackageFragmentDescriptor> =
        packages?.get(fqName)?.let(::listOf).orEmpty()

    override fun getSubPackagesOf(fqName: FqName, nameFilter: (Name) -> Boolean): Collection<FqName> =
        emptyList()
}

private class OptionalAnnotationClassDataFinder(classes: List<ClassData>) : ClassDataFinder {
    val classIdToData = classes.associateBy { (nameResolver, klass) -> nameResolver.getClassId(klass.fqName) }

    override fun findClassData(classId: ClassId): ClassData? = classIdToData[classId]
}

private class PackageFragmentForOptionalAnnotations(
    module: ModuleDescriptor,
    fqName: FqName,
    classNames: Set<Name>,
    classDescriptors: NotNullLazyValue<Map<Name, ClassDescriptor>>,
) : PackageFragmentDescriptorImpl(module, fqName) {
    private val scope = object : MemberScopeImpl() {
        override fun getContributedClassifier(name: Name, location: LookupLocation): ClassifierDescriptor? = classDescriptors()[name]

        override fun getContributedDescriptors(
            kindFilter: DescriptorKindFilter,
            nameFilter: (Name) -> Boolean
        ): Collection<DeclarationDescriptor> =
            if (kindFilter.acceptsKinds(DescriptorKindFilter.CLASSIFIERS_MASK)) classDescriptors().values else emptyList()

        override fun getClassifierNames(): Set<Name> = classNames

        override fun printScopeStructure(p: Printer) {
            p.print("PackageFragmentForOptionalAnnotations{${classNames.joinToString(transform = Name::asString)}}")
        }
    }

    override fun getMemberScope(): MemberScope = scope
}
