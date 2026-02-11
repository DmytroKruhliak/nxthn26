package com.nxthn.AIChatbot.controller;


// Внутри вашего KingdomController или отдельного сервиса

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.*;

public class ThreatAnalyzer {

    public static class ThreatScore {
        public int playerId;
        public int totalDamageReceived = 0;
        public boolean isAggressor = false;

        public ThreatScore(int playerId) {
            this.playerId = playerId;
        }
    }

    // Обработка фазы переговоров (через combatActions)
    public Map<Integer, ThreatScore> analyzeThreats(NegotiationRequest request) {
        return process(request.playerTower.playerId, request.enemyTowers, request.combatActions);
    }

    // Обработка фазы боя (через previousAttacks)
    public Map<Integer, ThreatScore> analyzeThreatsForCombat(CombatRequest request) {
        return process(request.playerTower.playerId, request.enemyTowers, request.previousAttacks);
    }

    // Универсальный метод, использующий твой класс CombatRecord
    private Map<Integer, ThreatScore> process(int myId, List<Tower> enemies, List<CombatRecord> history) {
        Map<Integer, ThreatScore> threats = new HashMap<>();

        // Инициализируем карту для всех врагов из списка
        if (enemies != null) {
            for (Tower enemy : enemies) {
                threats.put(enemy.playerId, new ThreatScore(enemy.playerId));
            }
        }

        // Если истории атак нет, возвращаем пустую карту
        if (history == null) return threats;

        for (CombatRecord record : history) {
            // Проверяем, что атаковали именно нас
            if (record.action != null && record.action.targetId == myId) {
                ThreatScore score = threats.getOrDefault(record.playerId, new ThreatScore(record.playerId));
                score.isAggressor = true;
                score.totalDamageReceived += record.action.troopCount;
                threats.put(record.playerId, score);
            }
        }
        return threats;
    }
}
