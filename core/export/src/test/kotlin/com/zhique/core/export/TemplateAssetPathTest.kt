package com.zhique.core.export

import kotlin.test.Test
import kotlin.test.assertEquals

/** 防漂移：harvestTemplates 拷贝名（template-<variant>.apk）与运行期读取名必须一致。 */
class TemplateAssetPathTest {
    @Test
    fun min与full的资产路径() {
        assertEquals("templates/template-min.apk", templateAssetPath("min"))
        assertEquals("templates/template-full.apk", templateAssetPath("full"))
    }
}
