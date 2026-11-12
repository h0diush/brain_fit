package app.brainfit.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Карточка: поверхность `card` с тонкой рамкой — строгое разделение блоков без теней.
 * С [onClick] карточка нажимается целиком и чуть сжимается под пальцем.
 */
@Composable
fun BrainCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    contentPadding: Dp = Dimens.cardPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BrainTheme.colors
    val border = BorderStroke(Dimens.hairline, colors.surfaceSecondary)
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
    val interaction = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .let { m ->
                if (onClick == null) {
                    m
                } else {
                    m.pressScale(interaction)
                        .clip(shape)
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                            onClickLabel = onClickLabel,
                            role = Role.Button,
                            onClick = onClick,
                        )
                }
            },
        color = colors.card,
        contentColor = colors.textPrimary,
        shape = shape,
        border = border,
        content = body,
    )
}

/** Главная кнопка экрана: на всю ширину, высотой 56 dp, ставится внизу под большой палец. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale(interaction),
        shape = MaterialTheme.shapes.medium,
        interactionSource = interaction,
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Второстепенная кнопка: контур без заливки, чтобы главное действие на экране было одно. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale(interaction),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(Dimens.hairline, BrainTheme.colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = BrainTheme.colors.textPrimary),
        interactionSource = interaction,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Заголовок блока: строгая подпись прописными буквами с разрядкой. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
        color = BrainTheme.colors.textSecondary,
        modifier = modifier,
    )
}

/** Вторичный текст: пояснения и пустые состояния. */
@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = BrainTheme.colors.textSecondary,
        modifier = modifier,
    )
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = Dimens.hairline, color = BrainTheme.colors.surfaceSecondary)
}

/** Значение, которое при смене въезжает снизу, а старое уходит вверх. */
@Composable
fun AnimatedValue(
    value: String,
    style: TextStyle,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(targetState = value, transitionSpec = { Motion.valueChange(this) }, label = "value", modifier = modifier) {
        Text(it, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Плитка статистики: подпись сверху, крупное число, пояснение снизу. Для TalkBack читается одной фразой.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    accent: Boolean = false,
) {
    Column(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = listOfNotNull(label, value, caption).joinToString(", ")
        },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = BrainTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AnimatedValue(
            value = value,
            style = MaterialTheme.typography.headlineSmall,
            color = if (accent) BrainTheme.colors.accent else BrainTheme.colors.textPrimary,
        )
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = BrainTheme.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Ряд плиток равной ширины, разделённых тонкими линиями. */
@Composable
fun StatRow(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.gap),
        content = content,
    )
}

/** Направление изменения: лучше, хуже или так же. Смысл передаёт текст и значок, не только цвет. */
enum class Delta { BETTER, WORSE, SAME }

@Composable
fun DeltaBadge(text: String, delta: Delta, modifier: Modifier = Modifier) {
    val colors = BrainTheme.colors
    val color = when (delta) {
        Delta.BETTER -> colors.accent
        Delta.WORSE -> colors.error
        Delta.SAME -> colors.textSecondary
    }
    val icon = when (delta) {
        Delta.BETTER -> Icons.AutoMirrored.Rounded.TrendingUp
        Delta.WORSE -> Icons.AutoMirrored.Rounded.TrendingDown
        Delta.SAME -> Icons.AutoMirrored.Rounded.TrendingFlat
    }
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(colors.surfaceSecondary)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Полоса заполнения 0..1 — например, сколько игр тренировки сыграно. Заполнение плавно догоняет значение. */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier) {
    val colors = BrainTheme.colors
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(Motion.BASE, easing = Motion.Standard), label = "meter")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(colors.surfaceSecondary),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(colors.accent),
        )
    }
}

/**
 * Сегментированный переключатель: выбранный вариант отмечает подложка, которая переезжает к нему.
 * Каждый сегмент — цель касания 48 dp.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BrainTheme.colors
    val index = options.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.minTouch)
            .clip(MaterialTheme.shapes.medium)
            .border(Dimens.hairline, colors.surfaceSecondary, MaterialTheme.shapes.medium)
            .background(colors.card)
            .padding(4.dp),
    ) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(segment * index, tween(Motion.BASE, easing = Motion.Standard), label = "segment")
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .clip(MaterialTheme.shapes.small)
                .background(colors.surfaceSecondary),
        )
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(MaterialTheme.shapes.small)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(option) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) colors.textPrimary else colors.textSecondary,
                    )
                }
            }
        }
    }
}

/** Плашка уровня: «ур. 6». */
@Composable
fun LevelBadge(level: Int, modifier: Modifier = Modifier, calibration: Boolean = false) {
    val colors = BrainTheme.colors
    Text(
        text = if (calibration) "ур. $level · калибр." else "ур. $level",
        style = MaterialTheme.typography.labelMedium,
        color = colors.accent,
        modifier = modifier
            .border(Dimens.hairline, colors.accent, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
