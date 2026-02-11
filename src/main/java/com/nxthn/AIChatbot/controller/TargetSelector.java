package com.nxthn.AIChatbot.controller;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

class TargetSelector {
    public static Integer selectBestTarget(CombatRequest request,
            Map<Integer, ThreatAnalyzer.ThreatScore> threats,
            List<EconomicAnalyzer.EnemyEconomy> ecoLeaderboard) {

        // Оставляем только живых врагов
        List<Tower> aliveEnemies = request.enemyTowers.stream()
                .filter(e -> e.hp > 0)
                .toList();

        if (aliveEnemies.isEmpty()) return null;

        // 1. ОТВЕТКА (МЕСТЬ): Бьем того, кто нанес урон нам, если он еще жив
        Integer revengeId = threats.values().stream()
                .filter(t -> t.isAggressor && t.totalDamageReceived > 0)
                .max(Comparator.comparingInt(t -> t.totalDamageReceived))
                .map(t -> t.playerId)
                .filter(id -> aliveEnemies.stream().anyMatch(e -> e.playerId == id))
                .orElse(null);

        if (revengeId != null) return revengeId;

        // 2. ДИПЛОМАТИЯ: Если нас не бьют, мы никого не трогаем (возвращаем null)
        // Но если ты хочешь, чтобы он всё же кого-то бил (например, лидера),
        // убери проверку на агрессора ниже.
        return null;
    }
}
