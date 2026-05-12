package sample;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.awt.geom.Point2D;
import java.util.*;

/**
 * BT_7274 - "Protocolo 3: Proteger o Piloto"
 * Versão Definitiva: ARMA Base + GFT Error Correction + Anti-Clinger.
 */
public class BT_7274 extends AdvancedRobot {

    private static final Map<String, EnemyData> enemyMap = new HashMap<>();
    private List<Wave> activeWaves = new ArrayList<>(); 
    
    private String trackName = null;
    private double closestDistance = 10000;
    private long lastScanTime = 0;
    private int moveDirection = 1;
    private double lastEnemyEnergy = 100;

    // --- ESTRUTURA DE ONDAS ---
    static class Wave {
        double startX, startY, bulletSpeed, armaPredictedAngle;
        int direction;
        long fireTime;
        String enemyName;
    }

    // --- DADOS TÁTICOS (HÍBRIDO) ---
    static class EnemyData {
        double avgVelocity = 0;
        double avgHeadingChange = 0;
        double lastHeading = 0;
        
        // GFT Bins agora rastreiam o ERRO do ARMA. Índice 15 = ARMA perfeito (0 erro).
        int[] armaErrorBins = new int[31]; 
        
        List<Double> shotBearings = new ArrayList<>(); 
        boolean isClinger = false;
        int closeTicks = 0;

        void updateStats(double v, double h, double dist) {
            double hChange = Utils.normalRelativeAngle(h - lastHeading);
            avgVelocity = (v * 0.7) + (avgVelocity * 0.3);
            avgHeadingChange = (hChange * 0.7) + (avgHeadingChange * 0.3);
            lastHeading = h;

            if (!isClinger) {
                if (dist < 45) {
                    closeTicks++;
                    if (closeTicks > 20) isClinger = true;
                } else if (dist > 100) {
                    closeTicks = Math.max(0, closeTicks - 1);
                }
            }
        }

        void recordShot(double bearing) {
            shotBearings.add(bearing);
            if (shotBearings.size() > 20) shotBearings.remove(0); 
        }
        
        double predictNextShotAngle() {
            if (shotBearings.size() < 3) return 0;
            double lastDiff = shotBearings.get(shotBearings.size()-1) - shotBearings.get(shotBearings.size()-2);
            return shotBearings.get(shotBearings.size()-1) + lastDiff;
        }
    }

    public void run() {
        setBodyColor(new Color(50, 70, 50));
        setGunColor(new Color(200, 100, 0));
        setRadarColor(new Color(50, 50, 50));
        setScanColor(Color.cyan);
        setBulletColor(Color.orange);

        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);

        while (true) {
            if (trackName == null) setTurnRadarRight(360);
            execute();
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        if (getTime() - lastScanTime > 5) closestDistance = 10000;

        if (trackName == null || e.getName().equals(trackName) || e.getDistance() < closestDistance) {
            trackName = e.getName();
            closestDistance = e.getDistance();
            lastScanTime = getTime();

            EnemyData data = enemyMap.getOrDefault(e.getName(), new EnemyData());
            data.updateStats(e.getVelocity(), e.getHeadingRadians(), e.getDistance());

            double absBearing = getHeadingRadians() + e.getBearingRadians();
            double ex = getX() + Math.sin(absBearing) * e.getDistance();
            double ey = getY() + Math.cos(absBearing) * e.getDistance();
            int lateralDirection = (e.getVelocity() * Math.sin(e.getHeadingRadians() - absBearing) >= 0) ? 1 : -1;

            // 1. GFT: MEDIR O ERRO DO ARMA NOS TIROS ANTERIORES
            for (int i = 0; i < activeWaves.size(); i++) {
                Wave w = activeWaves.get(i);
                double distToWave = (getTime() - w.fireTime) * w.bulletSpeed;
                
                if (distToWave > Point2D.distance(w.startX, w.startY, ex, ey) - 18) {
                    if (w.enemyName.equals(e.getName())) {
                        double actualAngle = Math.atan2(ex - w.startX, ey - w.startY);
                        
                        // Diferença entre onde o inimigo realmente foi e onde o ARMA previu que ele iria
                        double error = Utils.normalRelativeAngle(actualAngle - w.armaPredictedAngle) * w.direction;
                        double maxEscapeAngle = Math.asin(8.0 / w.bulletSpeed);
                        
                        double factor = error / maxEscapeAngle;
                        int index = (int) Math.round((factor * 15) + 15);
                        index = Math.max(0, Math.min(30, index)); 
                        
                        data.armaErrorBins[index]++; 
                    }
                    activeWaves.remove(i);
                    i--; 
                }
            }

            // 2. ARIMA DODGING
            double energyDrop = lastEnemyEnergy - e.getEnergy();
            if (energyDrop > 0 && energyDrop <= 3.0) {
                data.recordShot(e.getBearingRadians());
                if (Math.abs(data.predictNextShotAngle()) < 0.3) moveDirection *= -1; 
            }
            lastEnemyEnergy = e.getEnergy();

            // 3. RADAR LOCK
            setTurnRadarRightRadians(Utils.normalRelativeAngle(absBearing - getRadarHeadingRadians()) * 2);

            // 4. MOVIMENTAÇÃO: ORBITAL + ANTI-CLINGER
            double desiredDist = data.isClinger ? 200 : 50;
            double aggression = (data.isClinger || e.getDistance() < 30) ? 0.02 : 0.005;
            if (!data.isClinger && e.getDistance() < 30) desiredDist = 90;

            double distError = e.getDistance() - desiredDist;
            double turnAngle = e.getBearingRadians() + (Math.PI / 2) - (distError * aggression * moveDirection);
            
            setTurnRightRadians(Utils.normalRelativeAngle(turnAngle));
            setAhead(150 * moveDirection);

            // 5. MIRA HÍBRIDA (ARMA Base + GFT Error Factor)
            double firePower = Math.min(3.0, 400.0 / e.getDistance());
            double bulletSpeed = 20 - (3 * firePower);
            
            // Passo A: Simulação ARMA (Previsão Matemática)
            double predX = getX() + Math.sin(absBearing) * e.getDistance();
            double predY = getY() + Math.cos(absBearing) * e.getDistance();
            double simHeading = e.getHeadingRadians();
            
            for (int i = 0; i < 100; i++) {
                double time = Math.hypot(getX() - predX, getY() - predY) / bulletSpeed;
                if (i >= time) break;
                simHeading += data.avgHeadingChange;
                predX += Math.sin(simHeading) * data.avgVelocity;
                predY += Math.cos(simHeading) * data.avgVelocity;
                predX = Math.max(18, Math.min(getBattleFieldWidth() - 18, predX));
                predY = Math.max(18, Math.min(getBattleFieldHeight() - 18, predY));
            }
            double baseArmaAngle = Math.atan2(predX - getX(), predY - getY());

            // Passo B: Calibração GFT (Aprendizado de Máquina)
            int bestIndex = 15; // Começa assumindo que o ARMA está perfeitamente certo
            for (int i = 0; i < 31; i++) {
                if (data.armaErrorBins[i] > data.armaErrorBins[bestIndex]) {
                    bestIndex = i;
                }
            }
            
            double maxEscapeAngle = Math.asin(8.0 / bulletSpeed);
            double bestErrorOffset = ((double)(bestIndex - 15) / 15.0) * maxEscapeAngle;
            
            // Passo C: Aplica o offset de erro à previsão base do ARMA
            double finalAngle = baseArmaAngle + (bestErrorOffset * lateralDirection);
            double gunTurn = Utils.normalRelativeAngle(finalAngle - getGunHeadingRadians());
            setTurnGunRightRadians(gunTurn);

            if (getGunHeat() == 0 && Math.abs(gunTurn) < 0.1) {
                setFire(firePower);
                
                // Registra o tiro para treinar o GFT
                Wave w = new Wave();
                w.startX = getX(); w.startY = getY();
                w.fireTime = getTime();
                w.bulletSpeed = bulletSpeed;
                w.armaPredictedAngle = baseArmaAngle; // Salva o que o ARMA previu
                w.direction = lateralDirection;
                w.enemyName = e.getName();
                activeWaves.add(w);
            }
            
            enemyMap.put(e.getName(), data);
        }
    }

    public void onHitWall(HitWallEvent e) {
        moveDirection *= -1; 
    }

    public void onHitRobot(HitRobotEvent e) {
        moveDirection *= -1;
        EnemyData data = enemyMap.getOrDefault(e.getName(), new EnemyData());
        data.isClinger = true;
        enemyMap.put(e.getName(), data);
    }

    public void onRobotDeath(RobotDeathEvent e) {
        if (e.getName().equals(trackName)) {
            trackName = null;
            closestDistance = 10000;
        }
    }
}