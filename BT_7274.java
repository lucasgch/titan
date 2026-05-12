package sample;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.util.*;

/**
 * BT_7274 - "Protocolo 3: Proteger o Piloto"
 * Versão Final: ARIMA Dodging + ARMA Aiming + 50px Orbit + Multi-round Memory.
 */
public class BT_7274 extends AdvancedRobot {

    // Memória Estática: persiste entre os rounds da mesma partida
    private static final Map<String, EnemyData> enemyMap = new HashMap<>();
    
    private String trackName = null;
    private double closestDistance = 10000;
    private long lastScanTime = 0;
    private boolean lowHealthMode = false;
    private int moveDirection = 1;
    private double lastEnemyEnergy = 100;

    // --- CLASSE DE DADOS (MODELOS ARIMA/ARMA) ---
    static class EnemyData {
        double avgVelocity = 0;
        double avgHeadingChange = 0;
        double lastHeading = 0;
        List<Double> shotBearings = new ArrayList<>(); 

        void updateStats(double v, double h) {
            double hChange = Utils.normalRelativeAngle(h - lastHeading);
            // Média Móvel Exponencial (EMA) para o modelo ARMA
            avgVelocity = (v * 0.7) + (avgVelocity * 0.3);
            avgHeadingChange = (hChange * 0.7) + (avgHeadingChange * 0.3);
            lastHeading = h;
        }

        void recordShot(double bearing) {
            shotBearings.add(bearing);
            if (shotBearings.size() > 20) shotBearings.remove(0);
        }
        
        double predictNextShotAngle() {
            if (shotBearings.size() < 3) return 0;
            // Lógica ARIMA: Diferença integrada para prever tendência de erro
            double lastDiff = shotBearings.get(shotBearings.size()-1) - shotBearings.get(shotBearings.size()-2);
            return shotBearings.get(shotBearings.size()-1) + lastDiff;
        }
    }

    public void run() {
        // Cores Vanguard-class
        setBodyColor(new Color(50, 70, 50));
        setGunColor(new Color(200, 100, 0));
        setRadarColor(new Color(50, 50, 50));
        setScanColor(Color.cyan);
        setBulletColor(Color.orange);

        // Desacoplamento total de componentes
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
        // Controle de foco de alvo
        if (getTime() - lastScanTime > 5) closestDistance = 10000;

        if (trackName == null || e.getName().equals(trackName) || e.getDistance() < closestDistance) {
            trackName = e.getName();
            closestDistance = e.getDistance();
            lastScanTime = getTime();

            // Recupera ou cria perfil do inimigo
            EnemyData data = enemyMap.getOrDefault(e.getName(), new EnemyData());
            data.updateStats(e.getVelocity(), e.getHeadingRadians());

            // 1. ARIMA DODGING (Esquiva Baseada em Energia)
            double energyDrop = lastEnemyEnergy - e.getEnergy();
            if (energyDrop > 0 && energyDrop <= 3.0) {
                data.recordShot(e.getBearingRadians());
                double predictedShot = data.predictNextShotAngle();
                // Se o tiro predito for perigoso, invertemos o curso
                if (Math.abs(predictedShot) < 0.3) { 
                    moveDirection *= -1; 
                }
            }
            lastEnemyEnergy = e.getEnergy();

            // 2. RADAR LOCK
            double absBearing = getHeadingRadians() + e.getBearingRadians();
            setTurnRadarRightRadians(Utils.normalRelativeAngle(absBearing - getRadarHeadingRadians()) * 2);

            // 3. MOVIMENTAÇÃO (Orbital 50px)
            if (getEnergy() <= 10) lowHealthMode = true;
            
            if (!lowHealthMode) {
                double distError = e.getDistance() - 50;
                // Cálculo perpendicular para orbitar e manter distância
                double turnAngle = e.getBearingRadians() + (Math.PI / 2) - (distError * 0.005 * moveDirection);
                setTurnRightRadians(Utils.normalRelativeAngle(turnAngle));
                setAhead(100 * moveDirection);
            } else {
                // Modo sobrevivência: Movimento evasivo simples contra paredes
                setAhead(100 * moveDirection);
            }

            // 4. MIRA PREDITIVA SOFISTICADA (ARMA + Iteração de tempo)
            double firePower = Math.min(3.0, 400.0 / e.getDistance());
            double bulletSpeed = 20 - (3 * firePower);
            
            double predX = getX() + Math.sin(absBearing) * e.getDistance();
            double predY = getY() + Math.cos(absBearing) * e.getDistance();
            double simHeading = e.getHeadingRadians();
            
            // Simulação de trajetória tick-a-tick
            for (int i = 0; i < 100; i++) {
                double time = Math.hypot(getX() - predX, getY() - predY) / bulletSpeed;
                if (i >= time) break;

                simHeading += data.avgHeadingChange;
                predX += Math.sin(simHeading) * data.avgVelocity;
                predY += Math.cos(simHeading) * data.avgVelocity;

                // Limites da arena (Wall Clipping)
                predX = Math.max(18, Math.min(getBattleFieldWidth() - 18, predX));
                predY = Math.max(18, Math.min(getBattleFieldHeight() - 18, predY));
            }

            double finalAngle = Math.atan2(predX - getX(), predY - getY());
            double gunTurn = Utils.normalRelativeAngle(finalAngle - getGunHeadingRadians());
            setTurnGunRightRadians(gunTurn);

            // Disparo de Precisão
            if (getGunHeat() == 0 && Math.abs(gunTurn) < 0.1) {
                setFire(firePower);
            }
            
            enemyMap.put(e.getName(), data);
        }
    }

    public void onHitWall(HitWallEvent e) {
        moveDirection *= -1; // Inverte direção ao bater na parede
    }

    public void onRobotDeath(RobotDeathEvent e) {
        if (e.getName().equals(trackName)) {
            trackName = null;
            closestDistance = 10000;
        }
    }
}