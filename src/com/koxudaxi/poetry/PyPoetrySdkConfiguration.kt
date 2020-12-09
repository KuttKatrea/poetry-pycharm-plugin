package com.koxudaxi.poetry

import com.jetbrains.python.sdk.configuration.PyProjectSdkConfigurationExtension
import com.intellij.execution.ExecutionException
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.jetbrains.python.sdk.*
import java.awt.BorderLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JPanel

class PyPoetrySdkConfiguration : PyProjectSdkConfigurationExtension {

    private val LOGGER = Logger.getInstance(PyPoetrySdkConfiguration::class.java)

    override fun isApplicable(module: Module): Boolean = module.pyProjectToml != null

    override fun createAndAddSdkForConfigurator(module: Module): Sdk? = createAndAddSDk(module, PoetryPySdkConfigurationCollector.Companion.Source.CONFIGURATOR)

    override fun getIntentionName(module: Module): String {
        return "Create a poetry environment using ${module.pyProjectToml?.name}"
    }

    override fun createAndAddSdkForInspection(module: Module): Sdk? = createAndAddSDk(module, PoetryPySdkConfigurationCollector.Companion.Source.INSPECTION)

    private fun createAndAddSDk(module: Module, source: PoetryPySdkConfigurationCollector.Companion.Source): Sdk? {
        val poetryEnvExecutable = askForEnvData(module, source) ?: return null
        PropertiesComponent.getInstance().poetryPath = poetryEnvExecutable.poetryPath
        return createPoetry(module)
    }

    private fun askForEnvData(module: Module, source: PoetryPySdkConfigurationCollector.Companion.Source): PyAddNewPoetryFromFilePanel.Data? {
        val poetryExecutable = getPoetryExecutable()?.absolutePath

        if (source == PoetryPySdkConfigurationCollector.Companion.Source.INSPECTION && validatePoetryExecutable(poetryExecutable) == null) {
            return PyAddNewPoetryFromFilePanel.Data(poetryExecutable!!)
        }

        var permitted = false
        var envData: PyAddNewPoetryFromFilePanel.Data? = null

        ApplicationManager.getApplication().invokeAndWait {
            val dialog = Dialog(module)

            permitted = dialog.showAndGet()
            envData = dialog.envData

            LOGGER.debug("Dialog exit code: ${dialog.exitCode}, $permitted")
        }

        PoetryPySdkConfigurationCollector.logPoetryDialog(
                module.project,
                permitted,
                source,
                if (poetryExecutable.isNullOrBlank()) PoetryPySdkConfigurationCollector.Companion.InputData.NOT_FILLED else PoetryPySdkConfigurationCollector.Companion.InputData.SPECIFIED
        )
        return if (permitted) envData else null
    }

    private fun createPoetry(module: Module): Sdk? {
        ProgressManager.progress("Setting up poetry environment")
        LOGGER.debug("Creating poetry environment")

        val basePath = module.basePath ?: return null
        val poetry = try {
            val init = StandardFileSystems.local().findFileByPath(basePath)?.findChild(PY_PROJECT_TOML)?.let { getPyProjectTomlForPoetry(it) } == null
            setupPoetry(FileUtil.toSystemDependentName(basePath), null, true, init)
        }
        catch (e: ExecutionException) {
            PoetryPySdkConfigurationCollector.logPoetry(module.project, PoetryPySdkConfigurationCollector.Companion.PoetryResult.CREATION_FAILURE)
            LOGGER.warn("Exception during creating poetry environment", e)
            showSdkExecutionException(null, e, "Failed To Create Poetry Environment")
            return null
        }

        val path = PythonSdkUtil.getPythonExecutable(poetry).also {
            if (it == null) {
                PoetryPySdkConfigurationCollector.logPoetry(module.project, PoetryPySdkConfigurationCollector.Companion.PoetryResult.NO_EXECUTABLE)
                LOGGER.warn("Python executable is not found: $poetry")
            }
        } ?: return null

        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path).also {
            if (it == null) {
                PoetryPySdkConfigurationCollector.logPoetry(module.project, PoetryPySdkConfigurationCollector.Companion.PoetryResult.NO_EXECUTABLE_FILE)
                LOGGER.warn("Python executable file is not found: $path")
            }
        } ?: return null

        PoetryPySdkConfigurationCollector.logPoetry(module.project, PoetryPySdkConfigurationCollector.Companion.PoetryResult.CREATED)

        LOGGER.debug("Setting up associated poetry environment: $path, $basePath")
        val sdk = SdkConfigurationUtil.setupSdk(
                ProjectJdkTable.getInstance().allJdks,
                file,
                PythonSdkType.getInstance(),
                false,
                null,
                suggestedSdkName(basePath)
        ) ?: return null

        ApplicationManager.getApplication().invokeAndWait {
            LOGGER.debug("Adding associated poetry environment: $path, $basePath")
            SdkConfigurationUtil.addSdk(sdk)
            sdk.isPoetry = true
            sdk.associateWithModule(module, null)
        }

        return sdk
    }

    private class Dialog(module: Module) : DialogWrapper(module.project, false, IdeModalityType.PROJECT) {

        private val panel = PyAddNewPoetryFromFilePanel(module)

        val envData
            get() = panel.envData

        init {
            title = "Setting Up Poetry Environment"
            init()
        }

        override fun createCenterPanel(): JComponent {
            return JPanel(BorderLayout()).apply {
                val border = IdeBorderFactory.createEmptyBorder(Insets(4, 0, 6, 0))
                val message = "File pyproject.toml contains project dependencies. Would you like to create a poetry environment using it?"

                add(
                        JBUI.Panels.simplePanel(JBLabel(message)).withBorder(border),
                        BorderLayout.NORTH
                )

                add(panel, BorderLayout.CENTER)
            }
        }

        override fun postponeValidation(): Boolean = false

        override fun doValidateAll(): List<ValidationInfo> = panel.validateAll()
    }
}