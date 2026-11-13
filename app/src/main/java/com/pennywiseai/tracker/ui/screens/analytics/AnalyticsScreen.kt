package com.pennywiseai.tracker.ui.screens.analytics

import androidx.compose.ui.res.pluralStringResource
import com.pennywiseai.tracker.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.pennywiseai.tracker.ui.effects.overScrollVertical
import com.pennywiseai.tracker.ui.effects.rememberOverscrollFlingBehavior
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.tracker.data.contacts.LocalMerchantDisplay
import com.pennywiseai.tracker.data.database.entity.ProfileEntity
import com.pennywiseai.tracker.presentation.common.TimePeriod
import com.pennywiseai.tracker.presentation.common.chipLabel
import com.pennywiseai.tracker.presentation.common.label
import com.pennywiseai.tracker.presentation.common.TransactionTypeFilter
import com.pennywiseai.tracker.ui.components.*
import com.pennywiseai.tracker.ui.components.cards.ListItemCardV2
import com.pennywiseai.tracker.ui.components.cards.PennyWiseCardV2
import com.pennywiseai.tracker.ui.components.cards.SectionHeaderV2
import com.pennywiseai.tracker.ui.components.CustomTitleTopAppBar
import com.pennywiseai.tracker.ui.components.filterIcon
import com.pennywiseai.tracker.ui.components.shortLabel
import com.pennywiseai.tracker.ui.icons.CategoryMapping
import com.pennywiseai.tracker.ui.theme.*
import com.pennywiseai.tracker.utils.CurrencyFormatter
import com.pennywiseai.tracker.utils.DateRangeUtils
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.math.BigDecimal
import com.pennywiseai.tracker.ui.icons.categoryLabel

private enum class CategoryViewType { CHART, LIST }

/** The Transactions slice an Analytics row drills into: its group plus the view's filters. */
data class TransactionsDrillDown(
    val category: String? = null,
    val merchant: String? = null,
    val account: String? = null,
    val tag: String? = null,
    /** A [TransactionTypeFilter] name; null (all types) for Spending, which Transactions can't show. */
    val type: String? = null,
    val period: String? = null,
    val currency: String? = null,
    val startDateEpochDay: Long? = null,
    val endDateEpochDay: Long? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel = hiltViewModel(),
    onNavigateToChat: () -> Unit = {},
    /** Opens one transaction's detail screen, so a group's rows can be read here without leaving Analytics. */
    onTransactionClick: (Long) -> Unit = {},
    onNavigateToHome: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()
    val transactionTypeFilter by viewModel.transactionTypeFilter.collectAsStateWithLifecycle()
    val defaultTypeFilter by viewModel.defaultTypeFilter.collectAsStateWithLifecycle()
    val selectedCurrency by viewModel.selectedCurrency.collectAsStateWithLifecycle()
    val availableCurrencies by viewModel.availableCurrencies.collectAsStateWithLifecycle()
    val customDateRange by viewModel.customDateRange.collectAsStateWithLifecycle()
    val budgetCycleStartDay by viewModel.budgetCycleStartDay.collectAsStateWithLifecycle()
    val isUnifiedMode by viewModel.isUnifiedMode.collectAsStateWithLifecycle()
    val chartType by viewModel.selectedChartType.collectAsStateWithLifecycle()
    val categoryFilter by viewModel.categoryFilter.collectAsStateWithLifecycle()
    val selectedProfileId by viewModel.selectedProfileId.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val accountFilter by viewModel.accountFilter.collectAsStateWithLifecycle()
    val accountOptions by viewModel.accountOptions.collectAsStateWithLifecycle()
    val isProEntitled by viewModel.isProEntitled.collectAsStateWithLifecycle()
    val groupBy by viewModel.groupBy.collectAsStateWithLifecycle()
    val unclubbedPayees by viewModel.unclubbedPayees.collectAsStateWithLifecycle()
    val allCategories by viewModel.allCategories.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var assigningCategoryPayee by remember { mutableStateOf<MerchantData?>(null) }
    // The merchant, category, account or tag whose transactions are open in the sheet, as
    // "kind:name"; kept across opening a transaction and coming back.
    var viewingGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var showUpgradeSheet by remember { mutableStateOf(false) }
    var showDateRangePicker by rememberSaveable { mutableStateOf(false) }
    var categoryViewType by rememberSaveable { mutableStateOf(CategoryViewType.CHART) }
    var tagViewType by rememberSaveable { mutableStateOf(CategoryViewType.CHART) }
    var showChartTypeSelector by remember { mutableStateOf(false) }
    var showPeriodMenu by remember { mutableStateOf(false) }
    var showTypeMenu by remember { mutableStateOf(false) }
    var showCurrencyMenu by remember { mutableStateOf(false) }
    var showCategoryMenu by remember { mutableStateOf(false) }
    var showProfileMenu by remember { mutableStateOf(false) }
    var showAccountMenu by remember { mutableStateOf(false) }
    var showGroupByMenu by remember { mutableStateOf(false) }

    // Remember scroll position across navigation
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }

    // Cache expensive operations
    val timePeriods = remember { TimePeriod.values().toList() }
    val customRangeLabel = remember(customDateRange) {
        DateRangeUtils.formatDateRange(customDateRange)
    }
    val periodLabel = selectedPeriod.label
    val periodChipLabel = remember(selectedPeriod, budgetCycleStartDay, customRangeLabel, periodLabel) {
        selectedPeriod.chipLabel(budgetCycleStartDay, customRangeLabel, periodLabel)
    }
    val primaryVisibleCurrency = availableCurrencies.firstOrNull() ?: selectedCurrency
    val hasCurrencyFilter = !isUnifiedMode &&
            availableCurrencies.size > 1 &&
            selectedCurrency.isNotBlank() &&
            !selectedCurrency.equals(primaryVisibleCurrency, ignoreCase = true)
    val hasActiveAnalyticsFilter = selectedPeriod != TimePeriod.THIS_MONTH ||
            customDateRange != null ||
            transactionTypeFilter != defaultTypeFilter ||
            categoryFilter != null ||
            accountFilter != null ||
            hasCurrencyFilter

    // Scroll behaviors for collapsible TopAppBar
    val scrollBehaviorSmall = TopAppBarDefaults.pinnedScrollBehavior()
    val scrollBehaviorLarge = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val hazeState = remember { HazeState() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehaviorLarge.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            CustomTitleTopAppBar(
                scrollBehaviorSmall = scrollBehaviorSmall,
                scrollBehaviorLarge = scrollBehaviorLarge,
                title = stringResource(R.string.analytics_title),
                hazeState = hazeState
            )
        }
    ) { paddingValues ->
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical()
            .hazeSource(hazeState)
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = Dimensions.Padding.content,
            end = Dimensions.Padding.content,
            top = paddingValues.calculateTopPadding() + Spacing.md,
            bottom = Dimensions.Component.bottomBarHeight + Spacing.md
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        flingBehavior = rememberOverscrollFlingBehavior { listState }
    ) {
        item {
            AnalyticsFilterBar(
                selectedPeriod = selectedPeriod,
                customRangeLabel = customRangeLabel,
                periodChipLabel = periodChipLabel,
                timePeriods = timePeriods,
                transactionTypeFilter = transactionTypeFilter,
                defaultTypeFilter = defaultTypeFilter,
                selectedCurrency = selectedCurrency,
                availableCurrencies = availableCurrencies,
                isUnifiedMode = isUnifiedMode,
                categoryFilter = categoryFilter,
                availableCategories = uiState.availableCategories,
                profiles = profiles,
                selectedProfileId = selectedProfileId,
                accountOptions = accountOptions,
                accountFilter = accountFilter,
                groupBy = groupBy,
                isProEntitled = isProEntitled,
                showGroupByMenu = showGroupByMenu,
                showPeriodMenu = showPeriodMenu,
                showTypeMenu = showTypeMenu,
                showCurrencyMenu = showCurrencyMenu,
                showCategoryMenu = showCategoryMenu,
                showProfileMenu = showProfileMenu,
                showAccountMenu = showAccountMenu,
                hasActiveFilter = hasActiveAnalyticsFilter,
                onGroupByClick = { showGroupByMenu = true },
                onGroupByDismiss = { showGroupByMenu = false },
                onGroupBySelected = { option ->
                    showGroupByMenu = false
                    // A Pro grouping without Pro opens the upgrade sheet; the view stays put.
                    if (option.allowedFor(isProEntitled) == option) {
                        viewModel.setGroupBy(option)
                    } else {
                        showUpgradeSheet = true
                    }
                },
                onPeriodClick = { showPeriodMenu = true },
                onPeriodDismiss = { showPeriodMenu = false },
                onPeriodSelected = { period ->
                    if (period == TimePeriod.CUSTOM) {
                        showDateRangePicker = true
                    } else {
                        viewModel.selectPeriod(period)
                    }
                    showPeriodMenu = false
                },
                onTypeClick = { showTypeMenu = true },
                onTypeDismiss = { showTypeMenu = false },
                onTypeSelected = { typeFilter ->
                    viewModel.setTransactionTypeFilter(typeFilter)
                    showTypeMenu = false
                },
                onCurrencyClick = { showCurrencyMenu = true },
                onCurrencyDismiss = { showCurrencyMenu = false },
                onCurrencySelected = { currency ->
                    viewModel.selectCurrency(currency)
                    showCurrencyMenu = false
                },
                onCategoryClick = { showCategoryMenu = true },
                onCategoryDismiss = { showCategoryMenu = false },
                onCategorySelected = { category ->
                    if (category == null) {
                        viewModel.clearCategoryFilter()
                    } else {
                        viewModel.setCategoryFilter(category)
                    }
                    showCategoryMenu = false
                },
                onProfileClick = { showProfileMenu = true },
                onProfileDismiss = { showProfileMenu = false },
                onProfileSelected = { profileId ->
                    viewModel.selectProfile(profileId)
                    showProfileMenu = false
                },
                onAccountClick = { showAccountMenu = true },
                onAccountDismiss = { showAccountMenu = false },
                onAccountSelected = { accountKey ->
                    viewModel.setAccountFilter(accountKey)
                    showAccountMenu = false
                },
                onResetFilters = {
                    viewModel.selectPeriod(TimePeriod.THIS_MONTH)
                    viewModel.resetTransactionTypeFilter()
                    viewModel.clearCategoryFilter()
                    viewModel.setAccountFilter(null)
                    if (!isUnifiedMode && primaryVisibleCurrency.isNotBlank()) {
                        viewModel.selectCurrency(primaryVisibleCurrency)
                    }
                    showPeriodMenu = false
                    showTypeMenu = false
                    showCurrencyMenu = false
                    showCategoryMenu = false
                    showAccountMenu = false
                }
            )
        }

        // Analytics Summary Card
        if (uiState.totalSpending > BigDecimal.ZERO || uiState.transactionCount > 0) {
            item {
                AnalyticsSummaryCard(
                    totalAmount = uiState.totalSpending,
                    transactionCount = uiState.transactionCount,
                    averageAmount = uiState.averageAmount,
                    topCategory = uiState.topCategory,
                    topCategoryPercentage = uiState.topCategoryPercentage,
                    currency = uiState.currency,
                    isLoading = uiState.isLoading,
                    forecast = uiState.forecast
                )
            }
        }

        // Chart Section with Type Selector
        if (uiState.spendingTrend.size >= 2) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SectionHeaderV2(
                        title = stringResource(R.string.analytics_trends),
                        action = {
                            Button(
                                onClick = { showChartTypeSelector = !showChartTypeSelector },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                contentPadding = PaddingValues(horizontal = Spacing.smd, vertical = Spacing.xs)
                            ) {
                                Icon(
                                    imageVector = when (chartType) {
                                        ChartType.LINE -> Icons.AutoMirrored.Filled.ShowChart
                                        ChartType.BAR -> Icons.Default.BarChart
                                        ChartType.HEATMAP -> Icons.Default.GridView
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(Dimensions.Icon.small)
                                )
                                Spacer(modifier = Modifier.width(Spacing.xs))
                                Text(
                                    text = when (chartType) {
                                        ChartType.LINE -> stringResource(R.string.analytics_chart_line)
                                        ChartType.BAR -> stringResource(R.string.analytics_chart_bar)
                                        ChartType.HEATMAP -> stringResource(R.string.analytics_chart_heatmap)
                                    },
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    )

                    // Expandable chart type selector card
                    AnimatedVisibility(visible = showChartTypeSelector) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.sm),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            ChartType.entries.forEach { type ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.medium)
                                        .clickable {
                                            viewModel.setChartType(type)
                                            showChartTypeSelector = false
                                        }
                                        .padding(horizontal = Spacing.md, vertical = Dimensions.Padding.listRowVertical),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.smd),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = when (type) {
                                                ChartType.LINE -> Icons.AutoMirrored.Filled.ShowChart
                                                ChartType.BAR -> Icons.Default.BarChart
                                                ChartType.HEATMAP -> Icons.Default.GridView
                                            },
                                            contentDescription = null,
                                            tint = if (chartType == type)
                                                MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = when (type) {
                                                ChartType.LINE -> stringResource(R.string.analytics_chart_line_full)
                                                ChartType.BAR -> stringResource(R.string.analytics_chart_bar_full)
                                                ChartType.HEATMAP -> stringResource(R.string.analytics_chart_heatmap)
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (chartType == type)
                                                MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    if (chartType == type) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(Dimensions.Icon.medium)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Chart display with crossfade transition
                    Crossfade(
                        targetState = chartType,
                        label = "chart_transition"
                    ) { type ->
                        when (type) {
                            ChartType.LINE -> BalanceChart(
                                primaryCurrency = selectedCurrency,
                                balanceHistory = uiState.spendingTrend,
                                height = 220
                            )
                            ChartType.BAR -> SpendingBarChart(
                                primaryCurrency = selectedCurrency,
                                data = uiState.spendingTrend,
                                height = 220
                            )
                            ChartType.HEATMAP -> SpendingHeatmap(
                                data = uiState.spendingTrend
                            )
                        }
                    }
                }
            }
        }

        // Category Breakdown Section with Pie/List toggle
        if (groupBy == AnalyticsGroupBy.CATEGORY && uiState.categoryBreakdown.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SectionHeaderV2(
                        title = stringResource(R.string.analytics_top_categories),
                        action = {
                            IconButton(onClick = {
                                categoryViewType = if (categoryViewType == CategoryViewType.CHART) {
                                    CategoryViewType.LIST
                                } else {
                                    CategoryViewType.CHART
                                }
                            }) {
                                Icon(
                                    imageVector = if (categoryViewType == CategoryViewType.CHART)
                                        Icons.AutoMirrored.Filled.List
                                    else Icons.Default.PieChart,
                                    contentDescription = stringResource(R.string.analytics_toggle_view),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )

                    // Animated content swap
                    AnimatedContent(
                        targetState = categoryViewType,
                        transitionSpec = {
                            if (targetState == CategoryViewType.CHART) {
                                (slideInHorizontally { -it } + fadeIn()) togetherWith
                                    (slideOutHorizontally { it } + fadeOut()) using
                                    SizeTransform(clip = false)
                            } else {
                                (slideInHorizontally { it } + fadeIn()) togetherWith
                                    (slideOutHorizontally { -it } + fadeOut()) using
                                    SizeTransform(clip = false)
                            }
                        },
                        label = "category_view_transition"
                    ) { viewType ->
                        when (viewType) {
                            CategoryViewType.CHART -> CategoryPieChart(
                                categories = uiState.categoryBreakdown,
                                currency = selectedCurrency,
                                onCategoryClick = { category ->
                                    viewingGroup = "category:${category.name}"
                                }
                            )
                            CategoryViewType.LIST -> CategoryBreakdownCard(
                                categories = uiState.categoryBreakdown,
                                currency = selectedCurrency,
                                onCategoryClick = { category ->
                                    viewingGroup = "category:${category.name}"
                                }
                            )
                        }
                    }

                    // Explain why the bars can sum above the headline total: refunds are
                    // netted in full off the total, but a category bar can't go negative (#704).
                    if (uiState.refundNettedFromTotal > BigDecimal.ZERO) {
                        Text(
                            text = stringResource(
                                R.string.analytics_refund_note,
                                CurrencyFormatter.formatCurrency(uiState.refundNettedFromTotal, selectedCurrency)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.sm)
                        )
                    }
                }
            }
        }

        // Tag Breakdown Section with Pie/List toggle
        if (groupBy == AnalyticsGroupBy.TAG && uiState.tagBreakdown.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SectionHeaderV2(
                        title = stringResource(R.string.analytics_top_tags),
                        action = {
                            IconButton(onClick = {
                                tagViewType = if (tagViewType == CategoryViewType.CHART) {
                                    CategoryViewType.LIST
                                } else {
                                    CategoryViewType.CHART
                                }
                            }) {
                                Icon(
                                    imageVector = if (tagViewType == CategoryViewType.CHART)
                                        Icons.AutoMirrored.Filled.List
                                    else Icons.Default.PieChart,
                                    contentDescription = stringResource(R.string.analytics_toggle_view),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )

                    AnimatedContent(
                        targetState = tagViewType,
                        transitionSpec = {
                            if (targetState == CategoryViewType.CHART) {
                                (slideInHorizontally { -it } + fadeIn()) togetherWith
                                    (slideOutHorizontally { it } + fadeOut()) using
                                    SizeTransform(clip = false)
                            } else {
                                (slideInHorizontally { it } + fadeIn()) togetherWith
                                    (slideOutHorizontally { -it } + fadeOut()) using
                                    SizeTransform(clip = false)
                            }
                        },
                        label = "tag_view_transition"
                    ) { viewType ->
                        when (viewType) {
                            CategoryViewType.CHART -> TagPieChart(
                                tags = uiState.tagBreakdown,
                                currency = selectedCurrency,
                                onTagClick = { tag ->
                                    viewingGroup = "tag:${tag.name}"
                                }
                            )
                            CategoryViewType.LIST -> TagBreakdownCard(
                                tags = uiState.tagBreakdown,
                                currency = selectedCurrency,
                                onTagClick = { tag ->
                                    viewingGroup = "tag:${tag.name}"
                                }
                            )
                        }
                    }

                    if (uiState.tagsOverlap) {
                        Text(
                            text = stringResource(R.string.analytics_tag_overlap_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.sm)
                        )
                    }
                }
            }
        }

        // Day-of-week breakdown: there's no Transactions filter for a weekday, so rows don't drill in.
        if (groupBy == AnalyticsGroupBy.DAY_OF_WEEK && uiState.dayOfWeekBreakdown.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SectionHeaderV2(title = stringResource(R.string.analytics_by_day_of_week))
                    val barColor = MaterialTheme.colorScheme.primary
                    TagBreakdownCard(
                        tags = uiState.dayOfWeekBreakdown,
                        currency = selectedCurrency,
                        title = null,
                        visibleItemCount = uiState.dayOfWeekBreakdown.size,
                        barColor = { barColor },
                        onTagClick = null
                    )
                }
            }
        }

        // Top Merchants Section
        if (groupBy == AnalyticsGroupBy.MERCHANT && uiState.topMerchants.isNotEmpty()) {
            item {
                SectionHeaderV2(
                    title = stringResource(R.string.analytics_top_merchants)
                )
                // Clubbing toolbar row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = uiState.isClubbingEnabled,
                        onClick = { viewModel.toggleClubFrequentPayees() },
                        label = {
                            Text(
                                text = stringResource(R.string.analytics_club_payees),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = if (uiState.isClubbingEnabled) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.height(32.dp)
                    )

                    if (uiState.isClubbingEnabled) {
                        var showCriteriaMenu by remember { mutableStateOf(false) }
                        Box {
                            AssistChip(
                                onClick = { showCriteriaMenu = true },
                                label = {
                                    Text(
                                        text = stringResource(R.string.analytics_club_criteria_min, uiState.clubbingMinCount),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                modifier = Modifier.height(32.dp)
                            )
                            DropdownMenu(
                                expanded = showCriteriaMenu,
                                onDismissRequest = { showCriteriaMenu = false }
                            ) {
                                listOf(2, 3, 5, 10).forEach { count ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.analytics_club_criteria_min, count))
                                        },
                                        onClick = {
                                            viewModel.setClubbingMinCount(count)
                                            showCriteriaMenu = false
                                        },
                                        leadingIcon = if (uiState.clubbingMinCount == count) {
                                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                        } else null
                                    )
                                }
                            }
                        }

                        if (unclubbedPayees.isNotEmpty()) {
                            FilledTonalButton(
                                onClick = { viewModel.resetUnclubbedPayees() },
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = 0.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.analytics_reclub_all) + " (${unclubbedPayees.size})",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }

            // All Merchants with expandable list
            item {
                ExpandableList(
                    items = uiState.topMerchants,
                    visibleItemCount = 5,
                    modifier = Modifier.fillMaxWidth()
                ) { merchant ->
                    MerchantListItem(
                        merchant = merchant,
                        currency = selectedCurrency,
                        onClick = {
                            viewingGroup = "merchant:${merchant.name}"
                        },
                        onAssignCategoryClick = {
                            assigningCategoryPayee = merchant
                        },
                        onUnclubClick = {
                            viewModel.unclubPayee(merchant.name)
                        },
                        onReclubClick = {
                            viewModel.reclubPayee(merchant.name)
                        }
                    )
                }
            }
        }


        // By Account Section
        if (groupBy == AnalyticsGroupBy.ACCOUNT && uiState.accountBreakdown.isNotEmpty()) {
            item {
                SectionHeaderV2(
                    title = stringResource(R.string.analytics_by_account)
                )
            }

            item {
                ExpandableList(
                    items = uiState.accountBreakdown,
                    visibleItemCount = 3,
                    modifier = Modifier.fillMaxWidth()
                ) { account ->
                    AccountBreakdownListItem(
                        account = account,
                        currency = selectedCurrency,
                        onClick = { viewingGroup = "account:${account.key}" }
                    )
                }
            }
        }

        // Empty state
        if (uiState.topMerchants.isEmpty() && uiState.categoryBreakdown.isEmpty() && !uiState.isLoading) {
            item {
                EmptyAnalyticsState(onScanSmsClick = onNavigateToHome)
            }
        }
    }
    }

    if (showDateRangePicker) {
        CustomDateRangePickerDialog(
            onDismiss = { showDateRangePicker = false },
            onConfirm = { startDate, endDate ->
                viewModel.setCustomDateRange(startDate, endDate)
                showDateRangePicker = false
            },
            initialStartDate = customDateRange?.first,
            initialEndDate = customDateRange?.second
        )
    }

    if (showUpgradeSheet) {
        com.pennywiseai.tracker.presentation.paywall.UpgradeSheet(
            onDismiss = { showUpgradeSheet = false }
        )
    }

    // The sheet reads its group from the current data, so it follows the filters and
    // closes itself if the group is no longer in this view.
    val shownGroup = viewingGroup?.let { key ->
        val name = key.substringAfter(':')
        when (key.substringBefore(':')) {
            "merchant" -> uiState.topMerchants.firstOrNull { it.name == name }
                ?.let { GroupTransactions(it.name, it.amount, it.transactions) }
            "category" -> uiState.categoryBreakdown.firstOrNull { it.name == name }
                ?.let { GroupTransactions(it.name, it.amount, it.transactions) }
            "account" -> uiState.accountBreakdown.firstOrNull { it.key == name }
                ?.let { GroupTransactions(it.label, it.amount, it.transactions) }
            "tag" -> uiState.tagBreakdown.firstOrNull { it.name == name }
                ?.let { GroupTransactions(it.name, it.amount, it.transactions) }
            else -> null
        }
    }
    // The sheet is a window of its own, so it may only exist while this screen is in front. When
    // the Back gesture starts, the screen underneath is composed to preview the reveal; a sheet
    // window appearing then made Android lose the gesture's target ("Navigation window gone")
    // and cancel it. Its place is kept in [viewingGroup] and its scroll position below, so it
    // comes back exactly as it was.
    val inFront by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val sheetListState = rememberSaveable(viewingGroup, saver = LazyListState.Saver) { LazyListState() }
    if (shownGroup != null && inFront.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
        GroupTransactionsSheet(
            group = shownGroup,
            currency = selectedCurrency,
            listState = sheetListState,
            onTransactionClick = onTransactionClick,
            onDismiss = { viewingGroup = null }
        )
    }

    val currentPayee = assigningCategoryPayee
    if (currentPayee != null) {
        CategoryPickerDialog(
            categories = allCategories,
            currentCategory = currentPayee.dominantCategory,
            payeeName = currentPayee.name,
            transactionCount = currentPayee.transactionCount,
            onDismiss = { assigningCategoryPayee = null },
            onCategorySelected = { selectedCategoryName: String ->
                viewModel.assignCategoryToPayee(
                    merchantName = currentPayee.name,
                    transactionIds = currentPayee.transactions.map { it.id },
                    newCategory = selectedCategoryName
                )
                val msg = "Assigned $selectedCategoryName to ${currentPayee.transactionCount} transactions"
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                assigningCategoryPayee = null
            }
        )
    }

    if (showUpgradeSheet) {
        com.pennywiseai.tracker.presentation.paywall.UpgradeSheet(
            onDismiss = { showUpgradeSheet = false }
        )
    }
}

@Composable
private fun AnalyticsFilterBar(
    selectedPeriod: TimePeriod,
    customRangeLabel: String?,
    periodChipLabel: String,
    timePeriods: List<TimePeriod>,
    transactionTypeFilter: TransactionTypeFilter,
    defaultTypeFilter: TransactionTypeFilter,
    selectedCurrency: String,
    availableCurrencies: List<String>,
    isUnifiedMode: Boolean,
    categoryFilter: String?,
    availableCategories: List<String>,
    profiles: List<ProfileEntity>,
    selectedProfileId: Long?,
    accountOptions: List<com.pennywiseai.tracker.presentation.common.AccountOption>,
    accountFilter: String?,
    groupBy: AnalyticsGroupBy,
    isProEntitled: Boolean,
    showGroupByMenu: Boolean,
    showPeriodMenu: Boolean,
    showTypeMenu: Boolean,
    showCurrencyMenu: Boolean,
    showCategoryMenu: Boolean,
    showProfileMenu: Boolean,
    showAccountMenu: Boolean,
    hasActiveFilter: Boolean,
    onGroupByClick: () -> Unit,
    onGroupByDismiss: () -> Unit,
    onGroupBySelected: (AnalyticsGroupBy) -> Unit,
    onPeriodClick: () -> Unit,
    onPeriodDismiss: () -> Unit,
    onPeriodSelected: (TimePeriod) -> Unit,
    onTypeClick: () -> Unit,
    onTypeDismiss: () -> Unit,
    onTypeSelected: (TransactionTypeFilter) -> Unit,
    onCurrencyClick: () -> Unit,
    onCurrencyDismiss: () -> Unit,
    onCurrencySelected: (String) -> Unit,
    onCategoryClick: () -> Unit,
    onCategoryDismiss: () -> Unit,
    onCategorySelected: (String?) -> Unit,
    onProfileClick: () -> Unit,
    onProfileDismiss: () -> Unit,
    onProfileSelected: (Long?) -> Unit,
    onAccountClick: () -> Unit,
    onAccountDismiss: () -> Unit,
    onAccountSelected: (String?) -> Unit,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (hasActiveFilter) {
            item {
                AssistChip(
                    onClick = onResetFilters,
                    label = { Text(stringResource(R.string.analytics_filter_clear)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(Dimensions.Icon.small)
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = MaterialTheme.colorScheme.error,
                        leadingIconContentColor = MaterialTheme.colorScheme.error
                    )
                )
            }
        }

        // Group by (#422) — a view choice rather than a filter, so Clear leaves it alone.
        item {
            Box {
                ExpressiveFilterChip(
                    colors = analyticsFilterChipColors(),
                    border = analyticsFilterChipBorder(selected = groupBy != AnalyticsGroupBy.CATEGORY),
                    selected = groupBy != AnalyticsGroupBy.CATEGORY,
                    text = groupBy.label(),
                    icon = groupBy.icon(),
                    onClick = onGroupByClick
                )

                DropdownMenu(
                    expanded = showGroupByMenu,
                    onDismissRequest = onGroupByDismiss,
                    shape = MaterialTheme.shapes.large
                ) {
                    AnalyticsGroupBy.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label()) },
                            leadingIcon = {
                                Icon(
                                    if (groupBy == option) Icons.Default.Check else option.icon(),
                                    contentDescription = null
                                )
                            },
                            // Subtle Pro hint for free users; picking it opens the upgrade sheet.
                            trailingIcon = if (option.isPro && !isProEntitled) {
                                {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = stringResource(R.string.analytics_group_by_pro),
                                        tint = yellow_dark,
                                        modifier = Modifier.size(Dimensions.Icon.small)
                                    )
                                }
                            } else null,
                            onClick = { onGroupBySelected(option) }
                        )
                    }
                }
            }
        }

        if (profiles.isNotEmpty()) {
            item {
                val selectedProfileLabel = profiles.find { it.id == selectedProfileId }?.displayName()
                Box {
                    ExpressiveFilterChip(
                        colors = analyticsFilterChipColors(),
                        border = analyticsFilterChipBorder(selected = selectedProfileId != null),
                        selected = selectedProfileId != null,
                        text = selectedProfileLabel ?: stringResource(R.string.analytics_filter_all_profiles),
                        icon = profileFilterIcon(profiles, selectedProfileId),
                        onClick = onProfileClick
                    )

                    ProfileFilterDropdown(
                        expanded = showProfileMenu,
                        profiles = profiles,
                        selectedProfileId = selectedProfileId,
                        onProfileSelected = onProfileSelected,
                        onDismiss = onProfileDismiss
                    )
                }
            }
        }

        item {
            Box {
                ExpressiveFilterChip(
                    colors = analyticsFilterChipColors(),
                    border = analyticsFilterChipBorder(
                        selected = selectedPeriod != TimePeriod.THIS_MONTH || customRangeLabel != null
                    ),
                    selected = selectedPeriod != TimePeriod.THIS_MONTH || customRangeLabel != null,
                    text = periodChipLabel,
                    icon = Icons.Default.CalendarMonth,
                    onClick = onPeriodClick
                )

                DropdownMenu(
                    expanded = showPeriodMenu,
                    onDismissRequest = onPeriodDismiss,
                    shape = MaterialTheme.shapes.large
                ) {
                    timePeriods.forEach { period ->
                        DropdownMenuItem(
                            text = { Text(period.label) },
                            leadingIcon = {
                                if (selectedPeriod == period) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            },
                            onClick = { onPeriodSelected(period) }
                        )
                    }
                }
            }
        }

        item {
            Box {
                ExpressiveFilterChip(
                    colors = analyticsFilterChipColors(),
                    border = analyticsFilterChipBorder(
                        selected = transactionTypeFilter != defaultTypeFilter
                    ),
                    selected = transactionTypeFilter != defaultTypeFilter,
                    text = transactionTypeFilter.shortLabel(),
                    icon = transactionTypeFilter.filterIcon(),
                    onClick = onTypeClick
                )

                DropdownMenu(
                    expanded = showTypeMenu,
                    onDismissRequest = onTypeDismiss,
                    shape = MaterialTheme.shapes.large
                ) {
                    TransactionTypeFilter.values().forEach { typeFilter ->
                        DropdownMenuItem(
                            text = { Text(typeFilter.label) },
                            leadingIcon = {
                                if (transactionTypeFilter == typeFilter) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                } else {
                                    Icon(typeFilter.filterIcon(), contentDescription = null)
                                }
                            },
                            onClick = { onTypeSelected(typeFilter) }
                        )
                    }
                }
            }
        }

        if (availableCurrencies.size > 1 && !isUnifiedMode) {
            item {
                Box {
                    ExpressiveFilterChip(
                        colors = analyticsFilterChipColors(),
                        border = analyticsFilterChipBorder(
                            selected = selectedCurrency != availableCurrencies.firstOrNull()
                        ),
                        selected = selectedCurrency != availableCurrencies.firstOrNull(),
                        text = selectedCurrency.ifBlank { stringResource(R.string.analytics_filter_currency) },
                        icon = Icons.Default.CurrencyExchange,
                        onClick = onCurrencyClick
                    )

                    DropdownMenu(
                        expanded = showCurrencyMenu,
                        onDismissRequest = onCurrencyDismiss,
                        shape = MaterialTheme.shapes.large
                    ) {
                        availableCurrencies.forEach { currency ->
                            DropdownMenuItem(
                                text = { Text(currency) },
                                leadingIcon = {
                                    if (selectedCurrency == currency) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                },
                                onClick = { onCurrencySelected(currency) }
                            )
                        }
                    }
                }
            }
        }

        if (availableCategories.isNotEmpty() || categoryFilter != null) {
            item {
                Box {
                    ExpressiveFilterChip(
                        colors = analyticsFilterChipColors(),
                        border = analyticsFilterChipBorder(selected = categoryFilter != null),
                        selected = categoryFilter != null,
                        text = categoryFilter?.let { categoryLabel(it) } ?: stringResource(R.string.analytics_filter_category),
                        icon = Icons.Default.Category,
                        onClick = onCategoryClick
                    )

                    DropdownMenu(
                        expanded = showCategoryMenu,
                        onDismissRequest = onCategoryDismiss,
                        shape = MaterialTheme.shapes.large
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.analytics_filter_all_categories)) },
                            leadingIcon = {
                                if (categoryFilter == null) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                } else {
                                    Icon(Icons.Default.Category, contentDescription = null)
                                }
                            },
                            onClick = { onCategorySelected(null) }
                        )

                        availableCategories.forEach { category ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        categoryLabel(category),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = {
                                    if (categoryFilter == category) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    } else {
                                        CategoryIcon(
                                            category = category,
                                            size = Dimensions.Icon.small
                                        )
                                    }
                                },
                                onClick = { onCategorySelected(category) }
                            )
                        }
                    }
                }
            }
        }

        if (accountOptions.isNotEmpty()) {
            item {
                val selectedAccountLabel =
                    accountOptions.firstOrNull { it.key == accountFilter }?.label
                Box {
                    ExpressiveFilterChip(
                        colors = analyticsFilterChipColors(),
                        border = analyticsFilterChipBorder(selected = accountFilter != null),
                        selected = accountFilter != null,
                        text = selectedAccountLabel ?: stringResource(R.string.analytics_filter_account),
                        icon = Icons.Default.AccountBalanceWallet,
                        onClick = onAccountClick
                    )

                    DropdownMenu(
                        expanded = showAccountMenu,
                        onDismissRequest = onAccountDismiss,
                        shape = MaterialTheme.shapes.large
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.analytics_filter_all_accounts)) },
                            leadingIcon = {
                                if (accountFilter == null) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                } else {
                                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = null)
                                }
                            },
                            onClick = { onAccountSelected(null) }
                        )
                        accountOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = {
                                    if (accountFilter == option.key) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    } else {
                                        Icon(Icons.Default.AccountBalanceWallet, contentDescription = null)
                                    }
                                },
                                onClick = { onAccountSelected(option.key) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsGroupBy.label(): String = stringResource(
    when (this) {
        AnalyticsGroupBy.CATEGORY -> R.string.analytics_group_by_category
        AnalyticsGroupBy.MERCHANT -> R.string.analytics_group_by_merchant
        AnalyticsGroupBy.ACCOUNT -> R.string.analytics_group_by_account
        AnalyticsGroupBy.TAG -> R.string.analytics_group_by_tag
        AnalyticsGroupBy.DAY_OF_WEEK -> R.string.analytics_group_by_day_of_week
    }
)

private fun AnalyticsGroupBy.icon() = when (this) {
    AnalyticsGroupBy.CATEGORY -> Icons.Default.Category
    AnalyticsGroupBy.MERCHANT -> Icons.Default.Store
    AnalyticsGroupBy.ACCOUNT -> Icons.Default.AccountBalanceWallet
    AnalyticsGroupBy.TAG -> Icons.Default.Sell
    AnalyticsGroupBy.DAY_OF_WEEK -> Icons.Default.DateRange
}

@Composable
private fun analyticsFilterChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    labelColor = MaterialTheme.colorScheme.onSurface,
    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer
)

@Composable
private fun analyticsFilterChipBorder(selected: Boolean) = FilterChipDefaults.filterChipBorder(
    selected = selected,
    enabled = true,
    borderWidth = Dimensions.Elevation.none,
    selectedBorderWidth = Dimensions.Elevation.none
)

@Composable
private fun CategoryListItem(
    category: CategoryData,
    currency: String
) {
    val categoryInfo = CategoryMapping.categories[category.name]
        ?: CategoryMapping.categories["Others"]!!

    ListItemCardV2(
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(Dimensions.Icon.avatar)
                    .clip(CircleShape)
                    .background(CategoryMapping.colorFor(category.name).copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                CategoryIcon(
                    category = category.name,
                    size = Dimensions.Icon.medium,
                    tint = CategoryMapping.colorFor(category.name)
                )
            }
        },
        title = categoryLabel(category.name),
        subtitle = pluralStringResource(
            R.plurals.analytics_transaction_count,
            category.transactionCount,
            category.transactionCount
        ),
        amount = CurrencyFormatter.formatCurrency(category.amount, currency),
        trailingContent = {
            Text(
                text = stringResource(R.string.analytics_percent, category.percentage.toInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
private fun MerchantListItem(
    merchant: MerchantData,
    currency: String,
    onClick: () -> Unit = {},
    onAssignCategoryClick: (() -> Unit)? = null,
    onUnclubClick: (() -> Unit)? = null,
    onReclubClick: (() -> Unit)? = null
) {
    val merchantDisplay = LocalMerchantDisplay.current
    val displayName = merchantDisplay(merchant.name) ?: merchant.name

    if (merchant.isClubbed) {
        PennyWiseCardV2(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            contentPadding = Dimensions.Padding.cardCompact,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            onClick = onClick
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BrandIcon(
                        merchantName = merchant.name,
                        size = Dimensions.Icon.avatarLarge,
                        showBackground = true
                    )
                    Spacer(modifier = Modifier.width(Spacing.smd))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Text(
                                text = displayName,
                                style = PennyWiseText.rowTitle,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Text(
                                    text = stringResource(R.string.analytics_clubbed_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = Spacing.xs, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = pluralStringResource(
                                R.plurals.analytics_transaction_count,
                                merchant.transactionCount,
                                merchant.transactionCount
                            ) + if (merchant.averageAmount > BigDecimal.ZERO) {
                                " • avg " + CurrencyFormatter.formatCurrency(merchant.averageAmount, currency)
                            } else "",
                            style = PennyWiseText.metadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = CurrencyFormatter.formatCurrency(merchant.amount, currency),
                            style = PennyWiseText.amountRow,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Action chips row for clubbed payee
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val categoryName = merchant.dominantCategory ?: "Others"
                    AssistChip(
                        onClick = { onAssignCategoryClick?.invoke() },
                        label = {
                            Text(
                                text = categoryLabel(categoryName),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            CategoryIcon(
                                category = categoryName,
                                size = 14.dp,
                                tint = CategoryMapping.colorFor(categoryName)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = BorderStroke(1.dp, CategoryMapping.colorFor(categoryName).copy(alpha = 0.5f)),
                        modifier = Modifier.height(30.dp)
                    )

                    TextButton(
                        onClick = { onUnclubClick?.invoke() },
                        contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = 0.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LinkOff,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.analytics_unclub_payee),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    } else {
        val subtitle = pluralStringResource(
            if (merchant.isSubscription) R.plurals.analytics_transaction_count_subscription
            else R.plurals.analytics_transaction_count,
            merchant.transactionCount,
            merchant.transactionCount
        )

        ListItemCardV2(
            leadingContent = {
                BrandIcon(
                    merchantName = merchant.name,
                    size = Dimensions.Icon.avatarLarge,
                    showBackground = true
                )
            },
            title = displayName,
            subtitle = subtitle,
            amount = CurrencyFormatter.formatCurrency(merchant.amount, currency),
            onClick = onClick,
            trailingContent = if (merchant.isUnclubbedByUser) {
                {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = CurrencyFormatter.formatCurrency(merchant.amount, currency),
                                style = PennyWiseText.amountRow,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                        }
                        AssistChip(
                            onClick = { onReclubClick?.invoke() },
                            label = {
                                Text(
                                    text = stringResource(R.string.analytics_reclub_payee),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            } else null
        )
    }
}

@Composable
private fun AccountBreakdownListItem(
    account: AccountBreakdownData,
    currency: String,
    onClick: () -> Unit
) {
    ListItemCardV2(
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(Dimensions.Icon.avatar)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AccountBalanceWallet,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Dimensions.Icon.medium)
                )
            }
        },
        title = account.label,
        subtitle = pluralStringResource(
            R.plurals.analytics_transaction_count,
            account.transactionCount,
            account.transactionCount
        ),
        amount = CurrencyFormatter.formatCurrency(account.amount, currency),
        onClick = onClick,
        // Amount and share: a trailing slot replaces the card's own amount text.
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = CurrencyFormatter.formatCurrency(account.amount, currency),
                    style = PennyWiseText.amountRow,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = stringResource(R.string.analytics_percent, account.percentage.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun EmptyAnalyticsState(
    onScanSmsClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimensions.Padding.content),
        contentAlignment = Alignment.Center
    ) {
        PennyWiseEmptyState(
            icon = Icons.AutoMirrored.Filled.ShowChart,
            headline = stringResource(R.string.analytics_empty_headline),
            description = stringResource(R.string.analytics_empty_description),
            actionLabel = stringResource(R.string.analytics_empty_action),
            onAction = onScanSmsClick
        )
    }
}

/** What a tapped Analytics row stands for: its name, its total, and the rows behind it. */
private data class GroupTransactions(
    val title: String,
    val amount: BigDecimal,
    val transactions: List<com.pennywiseai.tracker.data.database.entity.TransactionEntity>
)

/**
 * One merchant's, category's, account's or tag's transactions for the view being looked
 * at — the same period, type, category and account filters as the list it was tapped in —
 * newest first. Tapping a row opens that transaction; the sheet stays where it was when
 * you come back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupTransactionsSheet(
    group: GroupTransactions,
    currency: String,
    listState: LazyListState,
    onTransactionClick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val transactions = remember(group.transactions) { group.transactions.sortedByDescending { it.dateTime } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${transactions.size} • " + CurrencyFormatter.formatCurrency(group.amount, currency),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        // The sheet is a window of its own. A row's icon normally animates into the detail screen
        // as a shared element, which cannot span two windows — it crashed on Back ("layouts are
        // not part of the same hierarchy"). Without a scope the rows use no shared element.
        CompositionLocalProvider(
            com.pennywiseai.tracker.ui.LocalSharedTransitionScope provides null,
            com.pennywiseai.tracker.ui.LocalNavAnimatedVisibilityScope provides null
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(transactions, key = { _, tx -> tx.id }) { index, tx ->
                    com.pennywiseai.tracker.ui.components.cards.TransactionItem(
                        transaction = tx,
                        listItemPosition = com.pennywiseai.tracker.ui.components.cards.ListItemPosition.from(index, transactions.size),
                        onClick = { onTransactionClick(tx.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryPickerDialog(
    categories: List<com.pennywiseai.tracker.data.database.entity.CategoryEntity>,
    currentCategory: String?,
    payeeName: String,
    transactionCount: Int,
    onDismiss: () -> Unit,
    onCategorySelected: (String) -> Unit
) {
    val merchantDisplay = LocalMerchantDisplay.current
    val displayName = merchantDisplay(payeeName) ?: payeeName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(
                    text = stringResource(R.string.analytics_assign_category),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Assign all $transactionCount transactions of \"$displayName\" to:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                val displayCats = if (categories.isNotEmpty()) {
                    categories.map { it.name }.distinct()
                } else {
                    CategoryMapping.categories.keys.toList()
                }

                items(displayCats.sorted()) { catName ->
                    val isSelected = catName.equals(currentCategory, ignoreCase = true)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onCategorySelected(catName) },
                        shape = MaterialTheme.shapes.medium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CategoryIcon(
                                category = catName,
                                size = 20.dp,
                                tint = CategoryMapping.colorFor(catName)
                            )
                            Spacer(modifier = Modifier.width(Spacing.md))
                            Text(
                                text = categoryLabel(catName),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.categories_action_cancel))
            }
        }
    )
}
