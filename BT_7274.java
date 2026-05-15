package sample;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.awt.geom.Point2D;
import java.util.*;

/**
 * BT_7274 - "Protocolo 3: Proteger o Piloto"
 * Versão VANGUARD PATCH 3.0: Letalidade Progressiva (Dynamic Firepower baseado no treino da IA).
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
    
    private double totalBearingChange = 0;
    private double lastAbsBearing = 0;
    private int orbitCounter = 0;
    private int randomOrbitLimit = 2 + (int)(Math.random() * 6);

    static class Wave { 
        double startX, startY, bulletSpeed, armaPredictedAngle; 
        int direction; long fireTime; String enemyName; 
        int[] segments; 
    }
    
    static class EnemyVirtualWave {
        Point2D.Double fireLoc;
        long fireTime;
        double bulletSpeed;
        double[] predictedBearings = new double[5]; 
        String enemyName;
    }

    static class EnemyData {
        double avgVelocity = 0, avgHeadingChange = 0, lastHeading = 0, lastVelocity = 0;
        int[][][][][] guessFactors = new int[3][3][3][2][31]; 
        boolean isClinger = false;
        int closeTicks = 0;
        double[] enemyGunScores = new double[5]; 

        void updateStats(double v, double h, double dist) {
            double hChange = Utils.normalRelativeAngle(h - lastHeading);
            avgVelocity = (v * 0.7) + (avgVelocity * 0.3);
            avgHeadingChange = (hChange * 0.7) + (avgHeadingChange * 0.3);
            lastHeading = h;
            lastVelocity = v; 

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
        setBodyColor(new Color(30, 40, 30)); 
        setGunColor(new Color(255, 140, 0)); 
        setRadarColor(new Color(20, 20, 20));
        setAdjustGunForRobotTurn(true); 
        setAdjustRadarForGunTurn(true); 
        setAdjustRadarForRobotTurn(true);

        while (true) {
            if (trackName == null || getRadarTurnRemaining() == 0) {
                setTurnRadarRight(360);
            }
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
            double previousEnemyVelocity = data.lastVelocity;
            
            data.updateStats(e.getVelocity(), e.getHeadingRadians(), e.getDistance());

            double absBearing = getHeadingRadians() + e.getBearingRadians();
            double ex = getX() + Math.sin(absBearing) * e.getDistance();
            double ey = getY() + Math.cos(absBearing) * e.getDistance();
            int lateralDirection = (e.getVelocity() * Math.sin(e.getHeadingRadians() - absBearing) >= 0) ? 1 : -1;
            int myLateralDirection = (getVelocity() * Math.sin(getHeadingRadians() - (absBearing + Math.PI)) >= 0) ? 1 : -1;

            double energyDrop = lastEnemyEnergy - e.getEnergy();
            if (energyDrop > 0 && energyDrop <= 3.0) {
                EnemyVirtualWave evw = new EnemyVirtualWave();
                evw.fireLoc = new Point2D.Double(ex, ey);
                evw.fireTime = getTime() - 1;
                evw.bulletSpeed = 20 - (3 * energyDrop);
                evw.enemyName = e.getName();
                
                double flightTime = e.getDistance() / evw.bulletSpeed;
                double myTurnRate = getHeadingRadians() - myLastHeading;
                
                evw.predictedBearings[0] = absBearing + Math.PI; 
                evw.predictedBearings[1] = Math.atan2((getX() + Math.sin(getHeadingRadians()) * getVelocity() * flightTime) - ex, (getY() + Math.cos(getHeadingRadians()) * getVelocity() * flightTime) - ey);
                evw.predictedBearings[2] = Math.atan2((getX() + Math.sin(getHeadingRadians() + myTurnRate * flightTime) * getVelocity() * flightTime) - ex, (getY() + Math.cos(getHeadingRadians() + myTurnRate * flightTime) * getVelocity() * flightTime) - ey);
                double maxMyEscape = Math.asin(8.0 / evw.bulletSpeed);
                evw.predictedBearings[3] = (absBearing + Math.PI) + (maxMyEscape * 0.8 * myLateralDirection);
                evw.predictedBearings[4] = (absBearing + Math.PI) - (maxMyEscape * 0.5 * myLateralDirection);

                enemyVirtualWaves.add(evw);
            }
            lastEnemyEnergy = e.getEnergy();
            myLastHeading = getHeadingRadians();

            for (int i = 0; i < enemyVirtualWaves.size(); i++) {
                EnemyVirtualWave evw = enemyVirtualWaves.get(i);
                if ((getTime() - evw.fireTime) * evw.bulletSpeed > Point2D.distance(evw.fireLoc.x, evw.fireLoc.y, getX(), getY())) {
                    if (evw.enemyName.equals(e.getName())) {
                        double myActualBearing = Math.atan2(getX() - evw.fireLoc.x, getY() - evw.fireLoc.y);
                        for (int j = 0; j < 5; j++) {
                            double error = Math.abs(Utils.normalRelativeAngle(myActualBearing - evw.predictedBearings[j]));
                            data.enemyGunScores[j] *= 0.85; 
                            data.enemyGunScores[j] += (2.0 - (error * 8.0)); 
                        }
                    }
                    enemyVirtualWaves.remove(i--);
                }
            }

            doWallSmoothingMovement(e, data, absBearing);
            
            int[] currentSegments = calculateSegments(e, ex, ey, previousEnemyVelocity);
            doAiming(e, data, absBearing, lateralDirection, ex, ey, currentSegments);

            setTurnRadarRightRadians(Utils.normalRelativeAngle(absBearing - getRadarHeadingRadians()) * 2);
            enemyMap.put(e.getName(), data);
        }
    }

    private int[] calculateSegments(ScannedRobotEvent e, double ex, double ey, double lastEnemyVel) {
        int distIdx = e.getDistance() < 250 ? 0 : (e.getDistance() < 500 ? 1 : 2);
        double absVel = Math.abs(e.getVelocity());
        int velIdx = absVel < 2 ? 0 : (absVel < 6 ? 1 : 2);
        double deltaV = absVel - Math.abs(lastEnemyVel);
        int accIdx = deltaV < -0.5 ? 0 : (deltaV > 0.5 ? 2 : 1);
        double pad = 120;
        int wallIdx = (ex < pad || ey < pad || ex > getBattleFieldWidth() - pad || ey > getBattleFieldHeight() - pad) ? 1 : 0;
        return new int[]{distIdx, velIdx, accIdx, wallIdx};
    }

    private void doWallSmoothingMovement(ScannedRobotEvent e, EnemyData data, double absBearing) {
        double desiredDist = (getOthers() > 1) ? 400 : 150;
        if (data.isClinger) desiredDist = 250;

        double wallSmoothingAngle = absBearing + (Math.PI / 2) * moveDirection;
        double distAdjustment = (e.getDistance() - desiredDist) / desiredDist;
        wallSmoothingAngle -= (distAdjustment * 0.5 * moveDirection);

        double x = getX(), y = getY(), width = getBattleFieldWidth(), height = getBattleFieldHeight();
        double wallStick = 140; 

        for (int i = 0; i < 100; i++) {
            double testX = x + Math.sin(wallSmoothingAngle) * wallStick * moveDirection;
            double testY = y + Math.cos(wallSmoothingAngle) * wallStick * moveDirection;
            if (testX < 18 || testY < 18 || testX > width - 18 || testY > height - 18) {
                wallSmoothingAngle += 0.1 * moveDirection;
            } else break;
        }

        setTurnRightRadians(Utils.normalRelativeAngle(wallSmoothingAngle - getHeadingRadians()));
        setMaxVelocity(8); setAhead(100 * moveDirection);

        if (lastAbsBearing != 0) totalBearingChange += Math.abs(Utils.normalRelativeAngle(absBearing - lastAbsBearing));
        lastAbsBearing = absBearing;
        if (totalBearingChange >= 2 * Math.PI) {
            totalBearingChange = 0; orbitCounter++;
            if (orbitCounter >= randomOrbitLimit) {
                moveDirection *= -1; orbitCounter = 0; randomOrbitLimit = 3 + (int)(Math.random() * 4);
            }
        }
    }

    private void doAiming(ScannedRobotEvent e, EnemyData data, double absBearing, int lateralDirection, double ex, double ey, int[] currentSegs) {
        // --- 1. RESOLUÇÃO DE ONDAS NO AR ---
        for (int i = 0; i < activeWaves.size(); i++) {
            Wave w = activeWaves.get(i);
            if ((getTime() - w.fireTime) * w.bulletSpeed > Point2D.distance(w.startX, w.startY, ex, ey) - 18) {
                if (w.enemyName.equals(e.getName())) {
                    double actualAngle = Math.atan2(ex - w.startX, ey - w.startY);
                    double error = Utils.normalRelativeAngle(actualAngle - w.armaPredictedAngle) * w.direction;
                    int index = (int) Math.round(((error / Math.asin(8.0 / w.bulletSpeed)) * 15) + 15);
                    index = Math.max(0, Math.min(30, index));
                    
                    int[] s = w.segments;
                    data.guessFactors[s[0]][s[1]][s[2]][s[3]][index]++; 
                }
                activeWaves.remove(i--); 
            }
        }

        // --- 2. CÁLCULO DE CONFIANÇA (TREINAMENTO DA IA) ---
        int[] specificBins = data.guessFactors[currentSegs[0]][currentSegs[1]][currentSegs[2]][currentSegs[3]];
        int bestIndex = 15; 
        int highestConfidence = 0; // Quantas vezes acertamos NESSE cenário exato
        for (int i = 0; i < 31; i++) {
            if (specificBins[i] > highestConfidence) {
                highestConfidence = specificBins[i];
                bestIndex = i;
            }
        }

        double bestDefenseScore = data.enemyGunScores[data.getBestEnemyGun()]; // Nossa segurança

        // --- 3. PODER DE FOGO DINÂMICO (NOVO) ---
        // Começamos atirando fraco (1.0) para coletar dados sem gastar energia à toa.
        double basePower = 1.0; 
        
        // Bônus ofensivo: +0.4 de força para cada hit mapeado nesse segmento (máx +1.5)
        double gfBonus = Math.min(1.5, highestConfidence * 0.4); 
        
        // Bônus defensivo: Se estamos desviando bem (score > 5), podemos nos dar ao luxo de gastar bateria
        double defenseBonus = (bestDefenseScore > 5.0) ? 0.5 : 0.0;

        double firePower = basePower + gfBonus + defenseBonus;
        
        // Travas de segurança do sistema
        firePower = Math.min(firePower, 400.0 / Math.max(1, e.getDistance())); // Tiros longos são mais fracos
        firePower = Math.min(firePower, 3.0); // Limite hard do canhão
        firePower = Math.min(firePower, getEnergy() / 6.0); // Preserva a própria energia se estiver morrendo
        firePower = Math.min(firePower, (e.getEnergy() / 4.0) + 0.1); // Não dá overkill atirando 3.0 num inimigo com 0.5 HP
        firePower = Math.max(0.1, firePower); // Força mínima

        double bulletSpeed = 20 - (3 * firePower);
        
        // --- 4. PREDIÇÃO DO CANHÃO (ARMA + GF) ---
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
        double finalAngle = baseArmaAngle + (((double)(bestIndex - 15) / 15.0) * Math.asin(8.0 / bulletSpeed) * lateralDirection);
        double gunTurn = Utils.normalRelativeAngle(finalAngle - getGunHeadingRadians());
        setTurnGunRightRadians(gunTurn);

        if (getGunHeat() == 0 && Math.abs(gunTurn) <= Math.max(Math.atan(36.0 / e.getDistance()), 0.05)) {
            setFire(firePower);
            Wave w = new Wave(); w.startX = getX(); w.startY = getY(); w.fireTime = getTime();
            w.bulletSpeed = bulletSpeed; w.armaPredictedAngle = baseArmaAngle; w.direction = lateralDirection; 
            w.enemyName = e.getName();
            w.segments = currentSegs; 
            activeWaves.add(w);
        }
    }

    public void onHitWall(HitWallEvent e) { moveDirection *= -1; totalBearingChange = 0; }
    public void onHitRobot(HitRobotEvent e) { moveDirection *= -1; }
    public void onRobotDeath(RobotDeathEvent e) {
        if (e.getName().equals(trackName)) { trackName = null; closestDistance = 10000; }
    }
}