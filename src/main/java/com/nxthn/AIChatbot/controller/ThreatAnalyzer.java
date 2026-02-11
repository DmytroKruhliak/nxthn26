package com.nxthn.AIChatbot.controller;


// Внутри вашего KingdomController или отдельного сервиса

import java.util.HashMap;
import java.util.Map;

public class ThreatAnalyzer {

    public static class ThreatScore {
        public int playerId;
        public int totalDamageReceived;
        public boolean isAggressor;

        public ThreatScore(int playerId) {
            this.playerId = playerId;
        }
    }

    public Map<Integer, ThreatScore> analyzeThreats(NegotiationRequest request) {
        Map<Integer, ThreatScore> threats = new HashMap<>();
        int myId = request.playerTower.playerId;

        // Инициализируем данные по всем врагам
        for (Tower enemy : request.enemyTowers) {
            threats.put(enemy.playerId, new ThreatScore(enemy.playerId));
        }

        // Анализируем combatActions (кто бил нас в прошлом ходу)
        if (request.combatActions != null) {
            for (CombatRecord record : request.combatActions) {
                // Если цель атаки - МЫ
                if (record.action.targetId == myId) {
                    ThreatScore score = threats.getOrDefault(record.playerId, new ThreatScore(record.playerId));
                    score.isAggressor = true;
                    score.totalDamageReceived += record.action.troopCount;
                    threats.put(record.playerId, score);
                }
            }
        }
        return threats;
    }
}
