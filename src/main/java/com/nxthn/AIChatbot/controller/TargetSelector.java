package com.nxthn.AIChatbot.controller;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

class TargetSelector {
    public static Integer selectBestTarget(CombatRequest request,
                                           Map<Integer, ThreatAnalyzer.ThreatScore> threats,
                                           List<EconomicAnalyzer.EnemyEconomy> ecoLeaderboard) {

        if (request.enemyTowers == null || request.enemyTowers.isEmpty()) return null;

        // ВАЖНО: Определяем, кому мы ПРЕДЛОЖИЛИ мир (повторяем логику из negotiate)
        // Чтобы случайно не ударить союзника
        List<Integer> potentialAllies = request.enemyTowers.stream()
                .filter(e -> !threats.get(e.playerId).isAggressor)
                .map(e -> e.playerId)
                .toList();

        // 1. Месть (самый приоритетный таргет)
        Integer revengeId = threats.values().stream()
                .filter(t -> t.isAggressor)
                .max(Comparator.comparingInt(t -> t.totalDamageReceived))
                .map(t -> t.playerId)
                .orElse(null);

        if (revengeId != null) return revengeId;

        // 2. Лидер по ресурсам (но НЕ наш союзник)
        for (EconomicAnalyzer.EnemyEconomy eco : ecoLeaderboard) {
            if (!potentialAllies.contains(eco.playerId)) {
                return eco.playerId;
            }
        }

        // 3. Если остались только союзники или нет выбора - бьем самого сильного врага
        return request.enemyTowers.get(0).playerId;
    }
}
