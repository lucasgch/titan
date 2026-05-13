package sample;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.awt.geom.Point2D;
import java.util.*;

/**
 * BT_7274 - "Protocolo 3: Proteger o Piloto"
 * Versão VANGUARD: Detecção de Mira Inimiga (5 Tipos) + Mutação de Movimento + Adaptação Melee/1v1.
 */
public class BT_7274 extends AdvancedRobot {

    private static final Map<String, EnemyData> enemyMap = new HashMap<>();
    private List<Wave> activeWaves = new ArrayList<>(); 
    private List<EnemyVirtualWave> enemyVirtualWaves = new ArrayList<>();
    
    private String trackName = null;
    private double closestDistance = 10000;
    private long lastScanTime = 0;
    private int moveDirection = 1;
    private double lastEnemyEnergy = 100;
    private double myLastHeading = 0;
    
    // --- VARIÁVEIS DE ORBITA E MUTAÇÃO ---
    private double totalBearingChange = 0;
    private double lastAbsBearing = 0;
    private int orbitCounter = 0;
    private int randomOrbitLimit = 2 + (int)(Math.random() * 6);

    // --- ESTRUTURAS DE ONDAS ---
    static class Wave { double startX, startY, bulletSpeed, armaPredictedAngle; int direction; long fireTime; String enemyName; }
    
    static class EnemyVirtualWave {
        Point2D.Double fireLoc;
        long fireTime;
        double bulletSpeed;
        double[] predictedBearings = new double[5]; // 0:HO, 1:Linear, 2:Circular, 3:GFT, 4:ARMA/ARIMA
        String enemyName;
    }

    // --- DADOS TÁTICOS DO INIMIGO ---
    static class EnemyData {
        double avgVelocity = 0, avgHeadingChange = 0, lastHeading = 0;
        int[] armaErrorBins = new int[31]; 
        boolean isClinger = false;
        int closeTicks = 0;
        
        // Placar de detecção de mira do inimigo
        double[] enemyGunScores = new double[5]; 

        void updateStats(double v, double h, double dist) {
            double hChange = Utils.normalRelativeAngle(h - lastHeading);
            avgVelocity = (v * 0.7) + (avgVelocity * 0.3);
            avgHeadingChange = (hChange * 0.7) + (avgHeadingChange * 0.3);
            lastHeading = h;

            if (!isClinger) {
                if (dist < 45) { closeTicks++; if (closeTicks > 20) isClinger = true; } 
                else if (dist > 100) { closeTicks = Math.max(0, closeTicks - 1); }
            }
        }
        
        int getBestEnemyGun() {
            int best = 0;
            for (int i = 1; i < 5; i++) if (enemyGunScores[i] > enemyGunScores[best]) best = i;
            return best;
        }
    }

    public void run() {
        setBodyColor(new Color(30, 40, 30)); setGunColor(new Color(255, 140, 0)); setRadarColor(new Color(20, 20, 20));
        setAdjustGunForRobotTurn(true); setAdjustRadarForGunTurn(true); setAdjustRadarForRobotTurn(true);

        while (true) {
            if (trackName == null) setTurnRadarRight(360);
            execute();
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        if (getTime() - lastScanTime > 5) closestDistance = 10000;

        if (trackName == null || e.getName().equals(trackName) || e.getDistance() < closestDistance || getOthers() > 1) {
            trackName = e.getName();
            closestDistance = e.getDistance();
            lastScanTime = getTime();

            EnemyData data = enemyMap.getOrDefault(e.getName(), new EnemyData());
            data.updateStats(e.getVelocity(), e.getHeadingRadians(), e.getDistance());

            double absBearing = getHeadingRadians() + e.getBearingRadians();
            double ex = getX() + Math.sin(absBearing) * e.getDistance();
            double ey = getY() + Math.cos(absBearing) * e.getDistance();
            int lateralDirection = (e.getVelocity() * Math.sin(e.getHeadingRadians() - absBearing) >= 0) ? 1 : -1;
            int myLateralDirection = (getVelocity() * Math.sin(getHeadingRadians() - (absBearing + Math.PI)) >= 0) ? 1 : -1;

            // --- DETECÇÃO DE DISPARO INIMIGO E ONDAS VIRTUAIS ---
            double energyDrop = lastEnemyEnergy - e.getEnergy();
            if (energyDrop > 0 && energyDrop <= 3.0) {
                EnemyVirtualWave evw = new EnemyVirtualWave();
                evw.fireLoc = new Point2D.Double(ex, ey);
                evw.fireTime = getTime() - 1;
                evw.bulletSpeed = 20 - (3 * energyDrop);
                evw.enemyName = e.getName();
                
                double flightTime = e.getDistance() / evw.bulletSpeed;
                double myTurnRate = getHeadingRadians() - myLastHeading;
                
                // Simulação dos 5 tipos de mira do inimigo:
                // 0. Head-On (Direta)
                evw.predictedBearings[0] = absBearing + Math.PI; 
                // 1. Linear
                evw.predictedBearings[1] = Math.atan2((getX() + Math.sin(getHeadingRadians()) * getVelocity() * flightTime) - ex, (getY() + Math.cos(getHeadingRadians()) * getVelocity() * flightTime) - ey);
                // 2. Circular
                evw.predictedBearings[2] = Math.atan2((getX() + Math.sin(getHeadingRadians() + myTurnRate * flightTime) * getVelocity() * flightTime) - ex, (getY() + Math.cos(getHeadingRadians() + myTurnRate * flightTime) * getVelocity() * flightTime) - ey);
                // 3. GFT (Assume que o inimigo atira no nosso ângulo máximo de escape estatístico)
                double maxMyEscape = Math.asin(8.0 / evw.bulletSpeed);
                evw.predictedBearings[3] = (absBearing + Math.PI) + (maxMyEscape * 0.8 * myLateralDirection);
                // 4. ARMA/ARIMA (Preditiva Complexa - Tenta antecipar que vamos frear ou inverter)
                evw.predictedBearings[4] = (absBearing + Math.PI) - (maxMyEscape * 0.5 * myLateralDirection);

                enemyVirtualWaves.add(evw);
            }
            lastEnemyEnergy = e.getEnergy();
            myLastHeading = getHeadingRadians();

            // --- PROCESSAR ONDAS VIRTUAIS (APRENDER A MIRA DELE) ---
            for (int i = 0; i < enemyVirtualWaves.size(); i++) {
                EnemyVirtualWave evw = enemyVirtualWaves.get(i);
                if ((getTime() - evw.fireTime) * evw.bulletSpeed > Point2D.distance(evw.fireLoc.x, evw.fireLoc.y, getX(), getY())) {
                    if (evw.enemyName.equals(e.getName())) {
                        double myActualBearing = Math.atan2(getX() - evw.fireLoc.x, getY() - evw.fireLoc.y);
                        // Verifica qual das 5 miras virtuais chegou mais perto de onde estamos agora
                        for (int j = 0; j < 5; j++) {
                            double error = Math.abs(Utils.normalRelativeAngle(myActualBearing - evw.predictedBearings[j]));
                            // Quanto menor o erro, mais pontos a mira virtual ganha
                            data.enemyGunScores[j] += Math.max(0, 1.0 - error);
                        }
                    }
                    enemyVirtualWaves.remove(i--);
                }
            }

            // --- MOVIMENTAÇÃO MUTÁVEL BASEADA NA DETECÇÃO ---
            doAdaptiveMovement(e, data, absBearing);

            // --- MIRA E TELEMETRIA (Seu chassi ARMA+GFT anterior) ---
            doAiming(e, data, absBearing, lateralDirection, ex, ey);

            setTurnRadarRightRadians(Utils.normalRelativeAngle(absBearing - getRadarHeadingRadians()) * 2);
            enemyMap.put(e.getName(), data);
        }
    }

    private void doAdaptiveMovement(ScannedRobotEvent e, EnemyData data, double absBearing) {
        double desiredDist = 200; // Padrão
        double turnAngle = e.getBearingRadians() + (Math.PI / 2);
        
        // 1. ANÁLISE DE CAMPO (Melee vs 1v1)
        if (getOthers() > 1) {
            // MELEE: Sobrevivência Mínima. Evita o centro, mantém muita distância (Kiting).
            desiredDist = 400;
            setMaxVelocity(6); // Conservador para não bater
            turnAngle -= ((e.getDistance() - desiredDist) * 0.01 * moveDirection);
            setTurnRightRadians(Utils.normalRelativeAngle(turnAngle));
            setAhead(100 * moveDirection);
        } else {
            // 1v1: Adaptação de Combate Ativa baseada na mira detectada
            int detectedGun = data.getBestEnemyGun();
            double aggression = (data.isClinger || e.getDistance() < 30) ? 0.02 : 0.005;
            desiredDist = data.isClinger ? 250 : 150;
            turnAngle -= ((e.getDistance() - desiredDist) * aggression * moveDirection);

            setTurnRightRadians(Utils.normalRelativeAngle(turnAngle));

            // Rastreamento Orbital para inversões seguras
            if (lastAbsBearing != 0) totalBearingChange += Math.abs(Utils.normalRelativeAngle(absBearing - lastAbsBearing));
            lastAbsBearing = absBearing;

            switch (detectedGun) {
                case 0: // CONTRA HEAD-ON: Ficar rápido e manter a órbita constante.
                    setMaxVelocity(8);
                    setAhead(150 * moveDirection);
                    if (totalBearingChange >= 2 * Math.PI) { orbitCounter++; totalBearingChange = 0; }
                    if (orbitCounter >= randomOrbitLimit) { moveDirection *= -1; orbitCounter = 0; randomOrbitLimit = 3 + (int)(Math.random() * 4); }
                    break;
                    
                case 1: // CONTRA LINEAR: Stop & Go. Acelera e freia para quebrar a matemática x=v*t.
                    setMaxVelocity((getTime() % 30 < 15) ? 8 : 0);
                    setAhead(100 * moveDirection);
                    break;
                    
                case 2: // CONTRA CIRCULAR: Wobble Orbit. Muda o raio da órbita bruscamente.
                    setMaxVelocity(8);
                    setTurnRightRadians(Utils.normalRelativeAngle(turnAngle + (Math.sin(getTime() / 10.0) * 0.5))); // Tremedeira
                    setAhead(100 * moveDirection);
                    break;
                    
                case 3: // CONTRA GFT: Achatamento Estatístico. Direções verdadeiramente caóticas (Surfing Aleatório).
                    setMaxVelocity(Math.random() > 0.2 ? 8 : 4);
                    if (Math.random() < 0.03) moveDirection *= -1; // Chance randômica de inversão sem aviso
                    setAhead(150 * moveDirection);
                    break;
                    
                case 4: // CONTRA ARMA/ARIMA: Quebra de Padrão. Oscilação rápida e curta.
                    setMaxVelocity(6 + Math.sin(getTime() / 5.0) * 2);
                    if (getTime() % (15 + (int)(Math.random() * 20)) == 0) moveDirection *= -1;
                    setAhead(80 * moveDirection);
                    break;
            }
            
            // Telemetria Console
            if (getTime() % 50 == 0) {
                String[] miras = {"Head-On", "Linear", "Circular", "GFT", "ARIMA/Preditiva"};
                out.println("Tático - Ameaça: [" + miras[detectedGun] + "] | Modo de Defesa Engajado.");
            }
        }
    }

    private void doAiming(ScannedRobotEvent e, EnemyData data, double absBearing, int lateralDirection, double ex, double ey) {
        // [CÓDIGO DE MIRA MANTIDO IGUAL À VERSÃO ANTERIOR (Otimizado por espaço)]
        for (int i = 0; i < activeWaves.size(); i++) {
            Wave w = activeWaves.get(i);
            if ((getTime() - w.fireTime) * w.bulletSpeed > Point2D.distance(w.startX, w.startY, ex, ey) - 18) {
                if (w.enemyName.equals(e.getName())) {
                    double actualAngle = Math.atan2(ex - w.startX, ey - w.startY);
                    double error = Utils.normalRelativeAngle(actualAngle - w.armaPredictedAngle) * w.direction;
                    int index = (int) Math.round(((error / Math.asin(8.0 / w.bulletSpeed)) * 15) + 15);
                    data.armaErrorBins[Math.max(0, Math.min(30, index))]++; 
                }
                activeWaves.remove(i--); 
            }
        }

        double firePower = Math.min(3.0, 400.0 / e.getDistance());
        double bulletSpeed = 20 - (3 * firePower);
        
        double gunTurnTicks = Math.abs(Utils.normalRelativeAngle(absBearing - getGunHeadingRadians())) / Rules.GUN_TURN_RATE_RADIANS;
        double myPredX = getX() + Math.sin(getHeadingRadians()) * getVelocity() * gunTurnTicks;
        double myPredY = getY() + Math.cos(getHeadingRadians()) * getVelocity() * gunTurnTicks;

        double predX = ex, predY = ey, simHeading = e.getHeadingRadians();
        for (int i = 0; i < 100; i++) {
            if (i >= (Math.hypot(myPredX - predX, myPredY - predY) / bulletSpeed) + gunTurnTicks) break;
            simHeading += data.avgHeadingChange;
            predX = Math.max(18, Math.min(getBattleFieldWidth() - 18, predX + Math.sin(simHeading) * data.avgVelocity));
            predY = Math.max(18, Math.min(getBattleFieldHeight() - 18, predY + Math.cos(simHeading) * data.avgVelocity));
        }
        
        double baseArmaAngle = Math.atan2(predX - myPredX, predY - myPredY);
        int bestIndex = 15; 
        for (int i = 0; i < 31; i++) if (data.armaErrorBins[i] > data.armaErrorBins[bestIndex]) bestIndex = i;
        
        double finalAngle = baseArmaAngle + (((double)(bestIndex - 15) / 15.0) * Math.asin(8.0 / bulletSpeed) * lateralDirection);
        double gunTurn = Utils.normalRelativeAngle(finalAngle - getGunHeadingRadians());
        setTurnGunRightRadians(gunTurn);

        if (getGunHeat() == 0 && Math.abs(gunTurn) <= Math.max(Math.atan(36.0 / e.getDistance()), 0.05)) {
            setFire(firePower);
            Wave w = new Wave(); w.startX = getX(); w.startY = getY(); w.fireTime = getTime();
            w.bulletSpeed = bulletSpeed; w.armaPredictedAngle = baseArmaAngle; w.direction = lateralDirection; w.enemyName = e.getName();
            activeWaves.add(w);
        }
    }

    public void onHitWall(HitWallEvent e) { moveDirection *= -1; totalBearingChange = 0; }
    public void onHitRobot(HitRobotEvent e) { moveDirection *= -1; totalBearingChange = 0; }
}