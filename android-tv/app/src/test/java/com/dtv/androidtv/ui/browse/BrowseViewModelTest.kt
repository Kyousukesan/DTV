package com.dtv.androidtv.ui.browse

import com.dtv.androidtv.data.model.Category
import com.dtv.androidtv.data.model.CategoryGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for LT-21 cate1/cate2 initial selection (desktop HomeScreen parity). */
class BrowseViewModelTest {

    @Test
    fun pickInitialSelection_prefersDefaultCategoryInsideItsGroup() {
        val groups = listOf(
            CategoryGroup(
                id = "g1",
                name = "网游竞技",
                categories = listOf(
                    Category("1", "英雄联盟"),
                    Category("2", "DOTA2"),
                ),
            ),
            CategoryGroup(
                id = "g2",
                name = "娱乐天地",
                categories = listOf(
                    Category("201", "颜值"),
                    Category("202", "音乐"),
                ),
            ),
        )
        val pick = BrowseViewModel.pickInitialSelection(groups, preferredCategoryId = "201")
        assertEquals("g2", pick.groupId)
        assertEquals(1, pick.groupIndex)
        assertEquals("201", pick.categoryId)
        assertEquals(0, pick.categoryIndex)
        assertEquals(2, pick.categories.size)
    }

    @Test
    fun pickInitialSelection_fallsBackToFirstCate2() {
        val groups = listOf(
            CategoryGroup(
                id = "g1",
                name = "热门",
                categories = listOf(Category("9", "全部直播")),
            ),
        )
        val pick = BrowseViewModel.pickInitialSelection(groups, preferredCategoryId = "missing")
        assertEquals("g1", pick.groupId)
        assertEquals("9", pick.categoryId)
        assertEquals(0, pick.groupIndex)
        assertEquals(0, pick.categoryIndex)
    }

    @Test
    fun pickInitialSelection_emptyTree() {
        val pick = BrowseViewModel.pickInitialSelection(emptyList(), preferredCategoryId = "1")
        assertEquals("", pick.groupId)
        assertEquals("", pick.categoryId)
    }
}
