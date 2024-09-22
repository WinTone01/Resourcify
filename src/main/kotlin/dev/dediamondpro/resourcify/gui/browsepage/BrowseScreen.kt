/*
 * This file is part of Resourcify
 * Copyright (C) 2023-2024 DeDiamondPro
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License Version 3 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.dediamondpro.resourcify.gui.browsepage

import dev.dediamondpro.resourcify.constraints.ChildLocationSizeConstraint
import dev.dediamondpro.resourcify.constraints.MaxComponentConstraint
import dev.dediamondpro.resourcify.constraints.WindowMinConstraint
import dev.dediamondpro.resourcify.elements.DropDown
import dev.dediamondpro.resourcify.elements.Paginator
import dev.dediamondpro.resourcify.gui.PaginatedScreen
import dev.dediamondpro.resourcify.gui.browsepage.components.ResourceCard
import dev.dediamondpro.resourcify.platform.Platform
import dev.dediamondpro.resourcify.services.ISearchData
import dev.dediamondpro.resourcify.services.IService
import dev.dediamondpro.resourcify.services.ProjectType
import dev.dediamondpro.resourcify.services.ServiceRegistry
import dev.dediamondpro.resourcify.util.localize
import dev.dediamondpro.resourcify.util.supplyAsync
import dev.dediamondpro.resourcify.util.toURI
import gg.essential.elementa.components.*
import gg.essential.elementa.components.input.UITextInput
import gg.essential.elementa.constraints.*
import gg.essential.elementa.constraints.animation.Animations
import gg.essential.elementa.dsl.*
import gg.essential.elementa.effects.OutlineEffect
import gg.essential.universal.UDesktop
import gg.essential.universal.UMatrixStack
import gg.essential.universal.UMinecraft
import net.minecraft.client.gui.GuiScreen
import java.awt.Color
import java.io.File
import java.util.concurrent.CompletableFuture

class BrowseScreen(
    private val type: ProjectType,
    private val downloadFolder: File,
    private val service: IService = ServiceRegistry.getDefaultService()
) : PaginatedScreen() {
    private var offset = 0
    private val selectedCategories = mutableListOf<String>()
    private var fetchingFuture: CompletableFuture<ISearchData?>? = null
    private var totalHits: Int = 0
    private var guiOpenedTime = UMinecraft.getTime()

    private val contentBox = UIContainer().constrain {
        x = CenterConstraint()
        y = 4.pixels()
        width = ChildBasedSizeConstraint(padding = 4f)
        height = 100.percent()
    } childOf window

    private val sideContainer = UIContainer().constrain {
        x = 0.pixels()
        y = 0.pixels()
        width = 160.pixels()
        height = 100.percent()
    } childOf contentBox

    private val mainBox = UIContainer().constrain {
        x = 0.pixels(alignOpposite = true)
        y = 0.pixels()
        width = WindowMinConstraint(528.pixels())
        height = 100.percent()
    } childOf contentBox

    private val adBox = UIBlock(color = Color(60, 130, 255, 100)) childOf mainBox

    private val headerBox = UIBlock(color = Color(0, 0, 0, 100)).constrain {
        x = 0.pixels()
        y = SiblingConstraint() + (if (service.getAdProvider().isAdAvailable()) 4 else 0).pixels()
        width = 100.percent()
        height = 29.pixels()
    } childOf mainBox

    private lateinit var searchBox: UITextInput

    private val projectScrollable = ScrollComponent(pixelsPerScroll = 30f, scrollAcceleration = 1.5f).constrain {
        x = 0.pixels()
        y = SiblingConstraint(padding = 4f)
        width = 100.percent()
        height = basicHeightConstraint { (this@BrowseScreen as GuiScreen).height - this.getY() }
    } childOf mainBox

    private val projectContainer = UIContainer().constrain {
        x = 0.pixels()
        y = SiblingConstraint(padding = 4f)
        width = 100.percent()
        height = ChildLocationSizeConstraint()
    } childOf projectScrollable

    private var minecraftVersions: Map<String, String>? = null
    private var versionDropDown: DropDown? = null
    private var sortDropDown: DropDown? = null

    init {
        sideBar()
        header()
    }

    private fun sideBar() {
        Paginator(this).constrain {
            x = 0.pixels()
            y = 0.pixels()
            width = 160.pixels()
            height = 29.pixels()
        } childOf sideContainer

        val sideBoxScrollable = ScrollComponent(pixelsPerScroll = 30f, scrollAcceleration = 1.5f).constrain {
            x = 0.pixels()
            y = SiblingConstraint(padding = 4f)
            width = 160.pixels()
            height = 100.percent() - 37.pixels()
        } childOf sideContainer

        val categoryContainer = UIBlock(color = Color(0, 0, 0, 100)).constrain {
            x = 0.pixels()
            y = SiblingConstraint(padding = 4f)
            width = 160.pixels()
            height = ChildLocationSizeConstraint()
        } childOf sideBoxScrollable

        UIText("resourcify.browse.source".localize()).constrain {
            x = 4.pixels()
            y = 4.pixels()
            textScale = 1.5f.pixels()
        } childOf categoryContainer
        DropDown(
            ServiceRegistry.getServices().map { it.getName() }, onlyOneOption = true,
            selectedOptions = mutableListOf(service.getName())
        ).constrain {
            x = 4.pixels()
            y = SiblingConstraint(padding = 4f)
            width = 100.percent() - 8.pixels()
        }.onSelectionUpdate {
            val newService = ServiceRegistry.getService(it.first()) ?: return@onSelectionUpdate
            if (newService == service) return@onSelectionUpdate
            replaceScreen { BrowseScreen(type, downloadFolder, newService) }
        } childOf categoryContainer

        val categoriesBox = UIContainer().constrain {
            x = 0.pixels()
            y = SiblingConstraint(padding = 4f)
            width = 100.percent()
            height = ChildLocationSizeConstraint()
        } childOf categoryContainer
        service.getCategories(type).thenAccept { categoriesHeaders ->
            Window.enqueueRenderOperation {
                for ((header, categories) in categoriesHeaders) {
                    UIText(header).constrain {
                        x = 4.pixels()
                        y = MaxConstraint(4.pixels(), SiblingConstraint(padding = 4f))
                        textScale = 1.5f.pixels()
                    } childOf categoriesBox

                    for ((id, category) in categories) {
                        val checkBox = UIContainer().constrain {
                            x = 0.pixels()
                            y = 0.pixels()
                            width = 7.pixels()
                            height = 7.pixels()
                        } effect OutlineEffect(Color.LIGHT_GRAY, 1f)

                        val check = UIBlock(Color(192, 192, 192, 0)).constrain {
                            x = 1.pixels()
                            y = 1.pixels()
                            width = 5.pixels()
                            height = 5.pixels()
                        } childOf checkBox

                        val categoryBox = UIContainer().constrain {
                            x = 4.pixels()
                            y = SiblingConstraint(4f)
                            width = ChildBasedSizeConstraint(4f)
                            height = ChildBasedMaxSizeConstraint()
                        }.onMouseClick {
                            if (it.mouseButton != 0) return@onMouseClick
                            if (selectedCategories.contains(id)) {
                                selectedCategories.remove(id)
                                check.animate {
                                    setColorAnimation(
                                        Animations.IN_OUT_QUAD,
                                        0.15f,
                                        Color(192, 192, 192, 0).toConstraint(),
                                        0f
                                    )
                                }
                            } else {
                                selectedCategories.add(id)
                                check.animate {
                                    setColorAnimation(
                                        Animations.IN_OUT_QUAD,
                                        0.15f,
                                        Color(192, 192, 192, 255).toConstraint(),
                                        0f
                                    )
                                }
                            }
                            loadPacks()
                        } childOf categoriesBox
                        checkBox childOf categoryBox

                        UIText(category).constrain {
                            x = SiblingConstraint(padding = 4f)
                            y = 0.pixels()
                            color = Color.LIGHT_GRAY.toConstraint()
                        } childOf categoryBox
                    }
                }
            }
        }
        val versionsBox = UIContainer().constrain {
            x = 0.pixels()
            y = SiblingConstraint()
            width = 100.percent()
            height = ChildLocationSizeConstraint()
        } childOf categoryContainer
        service.getMinecraftVersions().thenAccept {
            minecraftVersions = it
            Window.enqueueRenderOperation {
                UIText("resourcify.browse.minecraft_version".localize()).constrain {
                    x = 4.pixels()
                    y = 0.pixels()
                    textScale = 1.5f.pixels()
                } childOf versionsBox
                val currVersion = Platform.getMcVersion()
                versionDropDown = DropDown(
                    it.values.toList(), onlyOneOption = !service.canSelectMultipleMinecraftVersions(),
                    selectedOptions = if (it.values.toList().any { version -> version == currVersion })
                        mutableListOf(currVersion) else mutableListOf(), canDeSelect = true,
                    placeHolder = "resourcify.browse.minecraft_version.placeholder".localize()
                ).constrain {
                    x = 4.pixels()
                    y = SiblingConstraint(padding = 4f)
                    width = 100.percent() - 8.pixels()
                }.onSelectionUpdate {
                    loadPacks()
                } childOf versionsBox

                if (fetchingFuture == null && projectContainer.children.isEmpty()) {
                    loadPacks()
                }
            }
        }
    }

    private fun header() {
        val adProvider = service.getAdProvider()
        if (adProvider.isAdAvailable()) {
            adBox.constrain {
                x = 0.pixels()
                y = 0.pixels()
                width = 100.percent()
                height = 29.pixels()
            }.onMouseClick {
                // Prevents opening the ad link accidentally right when the GUI is opened
                if (it.mouseButton != 0 || guiOpenedTime + 500 > UMinecraft.getTime()) return@onMouseClick
                UDesktop.browse(adProvider.getUrl().toURI())
            }
            UIImage.ofResource(adProvider.getImagePath()).constrain {
                x = 4.pixels()
                y = 4.pixels()
                width = 21.pixels()
                height = 21.pixels()
            } childOf adBox
            UIWrappedText(adProvider.getText()).constrain {
                x = SiblingConstraint(padding = 4f)
                y = CenterConstraint()
                width = 100.percent() - 33.pixels()
            } childOf adBox
            UIImage.ofResource("/assets/resourcify/advertisement-text.png").constrain {
                x = 1.pixels(alignOpposite = true)
                y = 1.pixels(alignOpposite = true)
                width = 58.pixels()
                height = 5.pixels()
                color = Color.LIGHT_GRAY.toConstraint()
            } childOf adBox
        }

        searchBox = (UITextInput("resourcify.browse.search".localize(type.displayName.localize())).constrain {
            x = 6.pixels()
            y = CenterConstraint()
            width = 100.percent() - 89.pixels()
        }.onUpdate {
            loadPacks()
        }.onMouseClick {
            if (it.mouseButton != 0) return@onMouseClick
            grabWindowFocus()
        } childOf headerBox) as UITextInput
        sortDropDown = DropDown(
            service.getSortOptions().map { it.value.localize() },
            onlyOneOption = true,
            selectedOptions = mutableListOf(service.getSortOptions().values.first().localize())
        ).constrain {
            x = 5.pixels(true)
            y = CenterConstraint()
            width = 72.pixels()
        }.onSelectionUpdate {
            loadPacks()
        } childOf headerBox
    }

    private fun loadPacks(clear: Boolean = true) {
        fetchingFuture?.cancel(true)
        fetchingFuture = supplyAsync {
            if (clear) offset = 0
            else offset += 20
            service.search(
                searchBox.getText(),
                service.getSortOptions().keys.toList()[
                    sortDropDown?.options?.indexOf(sortDropDown!!.selectedOptions.first()) ?: 0
                ],
                minecraftVersions?.filter {
                    versionDropDown?.selectedOptions?.contains(it.value) ?: false
                }?.keys?.toList() ?: emptyList(),
                selectedCategories,
                offset,
                type
            )
        }.whenComplete { response, error ->
            error?.printStackTrace()
            if (error != null || response == null) return@whenComplete
            totalHits = response.totalCount
            val projects = response.projects
            Window.enqueueRenderOperation {
                if (clear) projectContainer.clearChildren()

                for (project in projects) {
                    val currentRow =
                        if (projectContainer.children.isEmpty() || projectContainer.children.last().children.size >= 2) {
                            UIContainer().constrain {
                                x = 0.pixels()
                                y = SiblingConstraint(padding = 4f)
                                width = 100.percent()
                                height = ChildBasedMaxSizeConstraint()
                            } childOf projectContainer
                        } else {
                            projectContainer.children.last()
                        }

                    val constraint: HeightConstraint = if (currentRow.children.isEmpty()) {
                        MaxComponentConstraint(ChildLocationSizeConstraint() + 4.pixels())
                    } else {
                        (currentRow.children.first().constraints.height as MaxComponentConstraint)
                            .createChildConstraint(ChildLocationSizeConstraint() + 4.pixels())
                    }
                    ResourceCard(service, project, type, downloadFolder).constrain {
                        x = SiblingConstraint(padding = 4f)
                        y = 0.pixels()
                        width = 50.percent() - 2.pixels()
                        height = constraint
                    } childOf currentRow
                }

                if (clear) projectScrollable.scrollToTop(false)
                fetchingFuture = null
            }
        }
    }

    override fun onDrawScreen(matrixStack: UMatrixStack, mouseX: Int, mouseY: Int, partialTicks: Float) {
        if (projectScrollable.verticalOffset + projectScrollable.verticalOverhang < 150 && fetchingFuture == null &&
            offset + 20 < totalHits
        ) {
            loadPacks(false)
        }
        super.onDrawScreen(matrixStack, mouseX, mouseY, partialTicks)
    }

    override fun goBack() {
        //#if MC == 10809
        if (type == ProjectType.AYCY_RESOURCE_PACK) {
            val screen = backScreens.lastOrNull()
            val previousScreenField = screen?.javaClass?.getDeclaredField("previousScreen")
            previousScreenField?.isAccessible = true
            displayScreen(previousScreenField?.get(screen) as GuiScreen?)
            cleanUp()
            return
        }
        //#endif
        super.goBack()
    }
}