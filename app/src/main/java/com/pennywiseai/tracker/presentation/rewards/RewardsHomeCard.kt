package com.pennywiseai.tracker.presentation.rewards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.utils.CurrencyFormatter

/** Home card: rewards earned this month and over three months. Tapping opens Card rewards. */
@Composable
fun RewardsHomeCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CardRewardsViewModel = hiltViewModel()
) {
    val s by viewModel.summary.collectAsStateWithLifecycle()
    // Nothing to show until there is a credit card.
    if (s.totalCards == 0) return
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.card_rewards_title), style = MaterialTheme.typography.titleMedium)
                if (s.configuredCards == 0) {
                    Text(stringResource(R.string.card_rewards_home_setup), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        stringResource(R.string.card_rewards_home_month, CurrencyFormatter.formatByCurrency(s.thisMonth)),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        stringResource(R.string.card_rewards_home_3m, CurrencyFormatter.formatByCurrency(s.last3Months)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
