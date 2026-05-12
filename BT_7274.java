package sample;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.util.*;

/**
 * BT_7274 - "Protocolo 3: Proteger o Piloto"
 * Versão Master: Perfilamento de Inimigos (Clinger Detection) + ARMA + ARIMA.
 */
public class BT_7274 extends AdvancedRobot {

    // Memória de longo prazo: Aprende os padrões e o TIPO do inimigo
    private static final Map<String, EnemyData> enemyMap = new HashMap<>();
    
    private String trackName = null;
    private double closestDistance = 10000;
    private long lastScanTime = 0;
    private int moveDirection = 1;
    private double lastEnemyEnergy = 100;

    // --- DADOS TÁTICOS E PERFILAMENTO ---
    static class EnemyData {
        double avgVelocity = 0;
        double avgHeadingChange = 0;
        double lastHeading = 0;
        List<Double> shotBearings = new ArrayList<>(); 
        
        // Dados de Detecção de Clinger
        boolean isClinger = false;
        int closeTicks = 0; // Quantos ticks ele passou perigosamente perto?

        void updateStats(double v, double h, double dist) {
            double hChange = Utils.normalRelativeAngle(h - lastHeading);
            
            // ARMA: Média Móvel Exponencial
            avgVelocity = (v * 0.7) + (avgVelocity * 0.3);
            avgHeadingChange = (hChange * 0.7) + (avgHeadingChange * 0.3);
            lastHeading = h;

            // Análise Comportamental: Se ele passa muito tempo a menos de 45px, ele é um clinger.
            if (!isClinger) {
                if (dist < 45) {
                    closeTicks++;
                    if (closeTicks > 20) { // Cerca de 20 ticks colado é o suficiente para confirmar
                        isClinger = true;
                    }
                } else if (dist > 100) {
                    closeTicks = Math.max(0, closeTicks - 1); // Reduz suspeita se ele se afastar
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
            if (trackName == null) {
                setTurnRadarRight(360);
            }
            execute();
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        if (getTime() - lastScanTime > 5) closestDistance = 10000;

        if (trackName == null || e.getName().equals(trackName) || e.getDistance() < closestDistance) {
            trackName = e.getName();
            closestDistance = e.getDistance();
            lastScanTime = getTime();

            // 1. CARREGA O MAPA E ATUALIZA O PERFIL
            EnemyData data = enemyMap.getOrDefault(e.getName(), new EnemyData());
            data.updateStats(e.getVelocity(), e.getHeadingRadians(), e.getDistance());

            // 2. ARIMA DODGING
            double energyDrop = lastEnemyEnergy - e.getEnergy();
            if (energyDrop > 0 && energyDrop <= 3.0) {
                data.recordShot(e.getBearingRadians());
                if (Math.abs(data.predictNextShotAngle()) < 0.3) { 
                    moveDirection *= -1; 
                }
            }
            lastEnemyEnergy = e.getEnergy();

            // 3. RADAR LOCK
            double absBearing = getHeadingRadians() + e.getBearingRadians();
            setTurnRadarRightRadians(Utils.normalRelativeAngle(absBearing - getRadarHeadingRadians()) * 2);

            // 4. MOVIMENTAÇÃO DINÂMICA (O Cérebro Tático)
            double desiredDist = 50;  // Padrão: Agressivo
            double aggression = 0.005;

            // Se o mapa diz que ele é um Clinger, a estratégia muda completamente
            if (data.isClinger) {
                desiredDist = 200; // Mantém o contato a longa distância
                aggression = 0.02; // Curva de fuga forte caso ele tente se aproximar
            } else if (e.getDistance() < 30) {
                // Reflexo de Sobrevivência (Caso um bot normal nos surpreenda)
                desiredDist = 90;
                aggression = 0.02;
            }

            double distError = e.getDistance() - desiredDist;
            double turnAngle = e.getBearingRadians() + (Math.PI / 2) - (distError * aggression * moveDirection);
            
            setTurnRightRadians(Utils.normalRelativeAngle(turnAngle));
            setAhead(150 * moveDirection);

            // 5. MIRA PREDITIVA ARMA
            double firePower = Math.min(3.0, 400.0 / e.getDistance());
            double bulletSpeed = 20 - (3 * firePower);
            
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

            double finalAngle = Math.atan2(predX - getX(), predY - getY());
            double gunTurn = Utils.normalRelativeAngle(finalAngle - getGunHeadingRadians());
            setTurnGunRightRadians(gunTurn);

            if (getGunHeat() == 0 && Math.abs(gunTurn) < 0.1) {
                setFire(firePower);
            }
            
            // Salva as atualizações no mapa
            enemyMap.put(e.getName(), data);
        }
    }

    public void onHitWall(HitWallEvent e) {
        moveDirection *= -1; 
    }

    public void onHitRobot(HitRobotEvent e) {
        moveDirection *= -1;
        
        // Se um robô tiver um comportamento suspeito como clinger, ele ganha o título de Clinger instantaneamente
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