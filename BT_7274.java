package sample;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.util.*;

public class BT_7274 extends AdvancedRobot {

    // Memória de longo prazo (Persiste entre rounds)
    private static final Map<String, EnemyProfile> enemyDatabase = new HashMap<>();
    
    String trackName = null;
    double closestDistance = 10000;
    long lastScanTime = 0;
    boolean lowHealthMode = false;
    int moveDirection = 1;

    // Perfil Avançado com Pesos Exponenciais
    static class EnemyProfile {
        double avgVelocity = 0;
        double avgHeadingChange = 0;
        double lastHeading = 0;
        double weight = 0.7; // Fator de aprendizado (0.7 = 70% novo, 30% antigo)

        void update(double v, double h) {
            double hChange = Utils.normalRelativeAngle(h - lastHeading);
            
            // Média Móvel Exponencial (mais sofisticado que média simples)
            avgVelocity = (v * weight) + (avgVelocity * (1 - weight));
            avgHeadingChange = (hChange * weight) + (avgHeadingChange * (1 - weight));
            
            lastHeading = h;
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
            if (getEnergy() <= 10 && !lowHealthMode) lowHealthMode = true;
            execute();
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        if (getTime() - lastScanTime > 5) closestDistance = 10000;

        if (trackName == null || e.getName().equals(trackName) || e.getDistance() < closestDistance) {
            trackName = e.getName();
            closestDistance = e.getDistance();
            lastScanTime = getTime();

            // Atualiza Perfil do Inimigo
            EnemyProfile profile = enemyDatabase.getOrDefault(e.getName(), new EnemyProfile());
            profile.update(e.getVelocity(), e.getHeadingRadians());
            enemyDatabase.put(e.getName(), profile);

            // 1. Radar Lock de Alta Precisão
            double absBearing = getHeadingRadians() + e.getBearingRadians();
            setTurnRadarRightRadians(Utils.normalRelativeAngle(absBearing - getRadarHeadingRadians()) * 2);

            // 2. Movimentação Orbital (50px) com Anti-Colisão
            if (!lowHealthMode) {
                if (Math.random() < 0.04) moveDirection *= -1;
                double distError = e.getDistance() - 50;
                // Quanto mais perto, mais perpendicular o robô tenta ficar
                double turnAngle = e.getBearingRadians() + (Math.PI / 2) - (distError * 0.006 * moveDirection);
                setTurnRightRadians(Utils.normalRelativeAngle(turnAngle));
                setAhead(100 * moveDirection);
            }

            // 3. MIRA PREDITIVA ITERATIVA (ESTILO ARMA)
            double firePower = Math.min(3, 400 / e.getDistance());
            double bulletSpeed = 20 - (3 * firePower);
            
            // Variáveis de simulação
            double predictedX = getX() + Math.sin(absBearing) * e.getDistance();
            double predictedY = getY() + Math.cos(absBearing) * e.getDistance();
            double currentHeading = e.getHeadingRadians();
            double currentVel = e.getVelocity();
            
            // Loop Iterativo: Simula o trajeto da bala e do robô ao mesmo tempo
            for (int i = 0; i < 100; i++) { // Max 100 ticks de previsão
                double distToPredicted = Math.hypot(getX() - predictedX, getY() - predictedY);
                double timeBulletNeeds = distToPredicted / bulletSpeed;
                
                // Se o tempo simulado alcançou o tempo que a bala leva, achamos o ponto de impacto
                if (i >= timeBulletNeeds) break;

                // Aplica a tendência do modelo ARMA (Heading change médio)
                currentHeading += profile.avgHeadingChange;
                
                // Simula movimento respeitando os limites de velocidade do Robocode
                predictedX += Math.sin(currentHeading) * currentVel;
                predictedY += Math.cos(currentHeading) * currentVel;

                // Wall Clipping: O robô não pode sair da arena
                predictedX = Math.max(18, Math.min(getBattleFieldWidth() - 18, predictedX));
                predictedY = Math.max(18, Math.min(getBattleFieldHeight() - 18, predictedY));
            }

            double finalAngle = Math.atan2(predictedX - getX(), predictedY - getY());
            double gunTurn = Utils.normalRelativeAngle(finalAngle - getGunHeadingRadians());
            
            setTurnGunRightRadians(gunTurn);

            // Controle de disparo: Só atira se o canhão estiver quase frio e a mira quente
            if (Math.abs(gunTurn) < 0.05 && getGunHeat() == 0) {
                setFire(firePower);
            }
        }
    }

    public void onHitWall(HitWallEvent e) {
        moveDirection *= -1;
    }

    public void onRobotDeath(RobotDeathEvent e) {
        if (e.getName().equals(trackName)) {
            trackName = null;
            closestDistance = 10000;
        }
    }
}