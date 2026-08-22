package nz.co.fordwalls.transportercam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nz.co.fordwalls.transportercam.CompanySummary
import nz.co.fordwalls.transportercam.MainViewModel

@Composable
fun CompanySelectScreen(viewModel: MainViewModel, onCompanySelected: () -> Unit) {
    val companies by viewModel.companies.collectAsState()
    val loading by viewModel.companiesLoading.collectAsState()
    val error by viewModel.companyLoadError.collectAsState()
    val offline by viewModel.companyListOffline.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Icon(Icons.Default.Business, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Choose your company", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Select your employer before signing in", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (offline) AssistChip(onClick = {}, label = { Text("Offline — showing saved companies") })
        Spacer(Modifier.height(24.dp))
        when {
            loading -> CircularProgressIndicator()
            error != null && companies.isEmpty() -> {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Button(onClick = viewModel::loadCompanies, modifier = Modifier.padding(top = 16.dp)) { Text("Retry") }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                items(companies, key = CompanySummary::id) { company ->
                    Card(modifier = Modifier.fillMaxWidth().clickable {
                        viewModel.selectCompany(company)
                        onCompanySelected()
                    }) {
                        Column(Modifier.padding(20.dp)) {
                            Text(company.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(company.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
