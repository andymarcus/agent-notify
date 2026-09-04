package com.agentnotify.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val lines = markdown.lines()
    Column(modifier = modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp)) {
        var inCodeBlock = false
        lines.forEach { raw ->
            if (raw.trim().startsWith("```")) {
                inCodeBlock = !inCodeBlock
            } else if (inCodeBlock) {
                Text(
                    text = raw,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.07f)).padding(8.dp),
                )
            } else {
                val trimmed = raw.trimStart()
                val (prefix, content) = when {
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> "• " to trimmed.drop(2)
                    trimmed.matches(Regex("^\\d+\\. .*")) -> trimmed.substringBefore(' ') + " " to trimmed.substringAfter(' ')
                    trimmed.startsWith("> ") -> "▎ " to trimmed.drop(2)
                    else -> "" to raw
                }
                val headingLevel = content.takeWhile { it == '#' }.length.takeIf { it in 1..6 && content.getOrNull(it) == ' ' }
                val body = if (headingLevel != null) content.drop(headingLevel + 1) else content
                Text(
                    text = buildAnnotatedString {
                        append(prefix)
                        appendInlineMarkdown(body)
                    },
                    fontSize = if (headingLevel != null) (22 - headingLevel * 2).sp else 15.sp,
                    fontWeight = if (headingLevel != null) FontWeight.Bold else FontWeight.Normal,
                    lineHeight = 21.sp,
                )
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInlineMarkdown(source: String) {
    var index = 0
    while (index < source.length) {
        val marker = when {
            source.startsWith("**", index) -> "**"
            source.startsWith("__", index) -> "__"
            source[index] == '`' -> "`"
            source[index] == '*' -> "*"
            source[index] == '_' -> "_"
            else -> null
        }
        if (marker == null) {
            append(source[index++])
            continue
        }
        val end = source.indexOf(marker, index + marker.length)
        if (end < 0) {
            append(marker)
            index += marker.length
            continue
        }
        val style = when (marker) {
            "**", "__" -> SpanStyle(fontWeight = FontWeight.Bold)
            "`" -> SpanStyle(fontFamily = FontFamily.Monospace, background = Color.Black.copy(alpha = 0.08f))
            else -> SpanStyle(fontStyle = FontStyle.Italic)
        }
        withStyle(style) { append(source.substring(index + marker.length, end)) }
        index = end + marker.length
    }
}

