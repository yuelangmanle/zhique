package com.zhique.core.agent

import com.zhique.core.agent.tools.FileTools
import com.zhique.core.agent.tools.GitTools
import com.zhique.core.agent.tools.WebTools

/** 默认工具全集（规格 §4.5 工具箱；screenshot_page 按 ctx.vision 由注册表过滤）。 */
fun defaultTools(): List<Tool> = WebTools.all() + FileTools.all() + GitTools.all()
