package sample;

import robocode.AdvancedRobot;
import robocode.HitWallEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;
import robocode.util.Utils;
import java.awt.Color;

/**
 * BT_7274 - "Protocolo 3: Proteger o Piloto"
 * - Radar Lock: Mantém o foco total no inimigo para atualizações em tempo real.
 * - Orbit 50px: Mantém distância de 50px orbitando o alvo (Strafing).
 * - Movimentação Aleatória: Muda de direção para confundir o inimigo.
 * - Modo Sobrevivência: Fuga pelas paredes e Mira Preditiva em vida baixa.
 */
public class BT_7274 extends AdvancedRobot {

    String trackName = null;
    double closestDistance = 10000;
    long lastScanTime = 0;
    
    boolean lowHealthMode = false;
    int moveDirection = 1; // 1 para frente, -1 para trás

    public void run() {
        // Cores Vanguard-class
        setBodyColor(new Color(50, 70, 50));
        setGunColor(new Color(200, 100, 0));
        setRadarColor(new Color(50, 50, 50));
        setScanColor(Color.cyan);
        setBulletColor(Color.orange);

        // Desacopla as peças para movimento independente
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);

        while (true) {
            // Se não tiver alvo, gira o radar para procurar
            if (trackName == null) {
                setTurnRadarRight(360);
            }

            // Ativa Sobrevivência se a vida estiver crítica
            if (getEnergy() <= 10 && !lowHealthMode) {
                lowHealthMode = true;
                out.println("Alerta: Protocolo de sobrevivência ativado!");
            }

            execute();
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        // Reset de distância se o escaneamento estiver velho
        if (getTime() - lastScanTime > 5) {
            closestDistance = 10000;
        }

        // Foca no alvo mais próximo
        if (trackName == null || e.getName().equals(trackName) || e.getDistance() < closestDistance) {
            trackName = e.getName();
            closestDistance = e.getDistance();
            lastScanTime = getTime();

            // 1. RADAR LOCK (Trava o radar no inimigo)
            // Calcula o ângulo absoluto do inimigo
            double absoluteBearing = getHeadingRadians() + e.getBearingRadians();
            // Faz o radar virar exatamente para onde o inimigo está, com um pequeno extra (2.0) para não perder o foco
            double radarTurn = Utils.normalRelativeAngle(absoluteBearing - getRadarHeadingRadians());
            setTurnRadarRightRadians(radarTurn * 2.0);

            // 2. MOVIMENTAÇÃO (Orbital Strafing a 50px)
            if (!lowHealthMode) {
                // 5% de chance de inverter a marcha
                if (Math.random() < 0.05) { moveDirection *= -1; }

                double distanceError = e.getDistance() - 50;
                double approachAngle = distanceError * 0.005; // Ajuste suave de aproximação
                
                // Ângulo de 90 graus em relação ao inimigo + ajuste de distância
                double turnAngle = e.getBearingRadians() + (Math.PI / 2) - (approachAngle * moveDirection);
                
                setTurnRightRadians(Utils.normalRelativeAngle(turnAngle));
                setAhead(150 * moveDirection);
            }

            // 3. MIRA (Preditiva em vida baixa, direta em vida normal)
            double firePower = Math.min(3, 400 / e.getDistance());
            double gunTurn;

                // Mira Preditiva Linear
                double enemyX = getX() + e.getDistance() * Math.sin(absoluteBearing);
                double enemyY = getY() + e.getDistance() * Math.cos(absoluteBearing);
                double bulletSpeed = 20 - (3 * firePower);
                double time = e.getDistance() / bulletSpeed;
                
                double futureX = enemyX + Math.sin(e.getHeadingRadians()) * e.getVelocity() * time;
                double futureY = enemyY + Math.cos(e.getHeadingRadians()) * e.getVelocity() * time;
                
                double bearingToFuture = Math.atan2(futureX - getX(), futureY - getY());
                gunTurn = Utils.normalRelativeAngle(bearingToFuture - getGunHeadingRadians());
            
            setTurnGunRightRadians(gunTurn);

            // Só atira se a mira estiver minimamente alinhada
            if (Math.abs(gunTurn) < 0.2) {
                setFire(firePower);
            }
        }
    }

    public void onHitWall(HitWallEvent e) {
        if (lowHealthMode) {
            // Contorna a parede
            setTurnLeft(90 - e.getBearing());
            setAhead(2000); 
        } else {
            // Bateu? Inverte a direção do strafing
            moveDirection *= -1;
            setAhead(100 * moveDirection);
        }
    }

    public void onRobotDeath(RobotDeathEvent e) {
        // Se o nosso alvo morreu, libera o radar para procurar outro
        if (e.getName().equals(trackName)) {
            trackName = null;
            closestDistance = 10000;
        }
    }
}