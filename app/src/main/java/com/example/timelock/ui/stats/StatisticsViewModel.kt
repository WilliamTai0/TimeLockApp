package com.example.timelock.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.timelock.data.local.AppDatabase
import com.example.timelock.domain.model.UsageStatSummary
import com.example.timelock.domain.usecase.CalculateStatisticsUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class StatisticsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val useCase = CalculateStatisticsUseCase(db.usageSessionDao())

    val summary: StateFlow<UsageStatSummary> = useCase.execute()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UsageStatSummary()
        )
}
