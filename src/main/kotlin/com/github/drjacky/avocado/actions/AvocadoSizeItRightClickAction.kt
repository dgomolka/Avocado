import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import java.io.*
import java.net.URL
import javax.swing.SwingUtilities
import kotlin.io.path.createTempFile

class AvocadoSizeItRightClickAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val projectObject: Project? = e.project
        if (projectObject != null) {
            val os = System.getProperty("os.name").toLowerCase()
            val executableName = when {
                os.contains("mac") -> "avocado-macos"
                os.contains("win") -> "avocado-win.exe"
                os.contains("linux") -> "avocado-linux"
                else -> {
                    println("Unsupported operating system: $os")
                    return
                }
            }
            val avocadoScriptPath = this::class.java.classLoader.getResource(executableName)

            if (avocadoScriptPath != null) {
                val psiFile = e.getData(CommonDataKeys.PSI_FILE)

                if (psiFile != null) {
                    if (isXmlFileInDrawableFolder(psiFile)) {
                        val task: Task.Backgroundable =
                            object : Task.Backgroundable(e.project, "Avocado Size It", true) {
                                override fun run(indicator: ProgressIndicator) {
                                    avocadoSizeIt(projectObject, avocadoScriptPath, executableName, psiFile.virtualFile)
                                }
                            }
                        ProgressManager.getInstance()
                            .runProcessWithProgressAsynchronously(task, BackgroundableProcessIndicator(task))
                    } else {
                        println("Right-clicked on XML file, but not in the expected folder")
                    }
                } else {
                    println("Right-clicked, but PSI file is null")
                }
            } else {
                println("avocadoScriptPath is blank!")
            }
        } else {
            println("Project path is null!")
        }
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        SwingUtilities.invokeLater {
            val psiFile = e.getData(CommonDataKeys.PSI_FILE)
            e.presentation.isEnabledAndVisible = isXmlFileInDrawableFolder(psiFile)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    private fun isXmlFileInDrawableFolder(psiFile: PsiFile?): Boolean {
        val parentFolder = psiFile?.virtualFile?.parent
        return psiFile is XmlFile &&
                psiFile.virtualFile?.extension == "xml" &&
                (parentFolder?.name == "drawable" || parentFolder?.name?.startsWith("drawable-") == true) &&
                parentFolder.parent?.name == "res"
    }

    private fun avocadoSizeIt(project: Project, avocadoScriptPath: URL, executableName: String, file: VirtualFile) {
        val fullPath = file.path
        try {
            val executableFile: File = if (avocadoScriptPath.protocol == "jar") {
                val tempFile = createTempFile(executableName).toFile()

                tempFile.deleteOnExit()

                // Copy the executable from the JAR to the temporary file
                avocadoScriptPath.openStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // Set execute permission on the temporary file
                tempFile.setExecutable(true)

                tempFile
            } else {
                File(avocadoScriptPath.toURI())
            }

            if (!executableFile.exists()) {
                println("Executable not found: ${executableFile.absolutePath}")
                return
            }

            val additionalParams = listOf("-i", fullPath)
            val command = mutableListOf(executableFile.absolutePath)
            command.addAll(additionalParams)

            val processBuilder = ProcessBuilder(command)

            // Redirect error stream to output stream
            processBuilder.redirectErrorStream(true)

            // Start the process
            val process = processBuilder.start()
            val inputStream = process.inputStream
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                println(line)
            }

            val exitCode = process.waitFor()
            println("Exit Code: $exitCode")

            reader.close()

            refreshFile(file, project)
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun refreshFile(file: VirtualFile, project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "", false) {
            override fun run(indicator: ProgressIndicator) {
                ApplicationManager.getApplication().invokeAndWait {
                    VfsUtil.markDirtyAndRefresh(true, false, true, file)
                }
            }
        })
    }

}