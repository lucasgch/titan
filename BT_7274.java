package sample;

import robocode.AdvancedRobot;
import robocode.HitWallEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;
import robocode.util.Utils;
import java.awt.Color;

/**
 * BT_7274 - "Protocolo 3: Proteger o Piloto"
 * Robô otimizado com movimentação aleatória e rastreio do alvo mais próximo.
 * Novo: Modo Perseguição se alvo < 100px.
 * No Modo Sobrevivência (<= 10 vida): foge pelas paredes e utiliza Mira Preditiva.
 */
public class BT_7274 extends AdvancedRobot {

    String trackName = null;
    double closestDistance = 10000;
    long lastScanTime = 0;
    boolean lowHealthMode = false;
    boolean enemyClose = false; // Flag para controlar o Modo Perseguição

    public void run() {
        // Cores inspiradas no Vanguard-class Titan
        setBodyColor(new Color(50, 70, 50));
        setGunColor(new Color(200, 100, 0));
        setRadarColor(new Color(50, 50, 50));
        setScanColor(Color.cyan);
        setBulletColor(Color.orange);

        // Desacopla o canhão e o radar do movimento do chassi
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);

        while (true) {
            // Verifica se a vida chegou em 10 ou menos
            if (getEnergy() <= 10 && !lowHealthMode) {
                lowHealthMode = true;
                out.println("Alerta Crítico: Protocolo de sobrevivência e mira preditiva ativados!");
                setAhead(2000); // Força o robô a correr reto até achar uma parede
            }

            // Gira o radar continuamente
            setTurnRadarRight(360);

            // Movimentação Aleatória só ocorre se a vida estiver boa E o inimigo estiver longe
            if (!lowHealthMode && !enemyClose) {
                if (getDistanceRemaining() == 0 && getTurnRemaining() == 0) {
                    setAhead((Math.random() * 300) - 150);
                    setTurnRight((Math.random() * 120) - 60);
                }
            }

            execute(); // Dispara todos os comandos pendentes simultaneamente
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        if (getTime() - lastScanTime > 5) {
            closestDistance = 10000;
        }

        if (trackName == null || e.getName().equals(trackName) || e.getDistance() < closestDistance) {
            trackName = e.getName();
            closestDistance = e.getDistance();
            lastScanTime = getTime();

            double firePower = Math.min(3, 400 / e.getDistance());
            double gunTurn;

            // ==========================================
            // CONTROLE DE MOVIMENTAÇÃO (Perseguição)
            // ==========================================
            if (e.getDistance() < 100) {
                enemyClose = true; // Pausa a movimentação aleatória do run()
                
                if (!lowHealthMode) {
                    // Vira o chassi (corpo do robô) na direção exata do inimigo e avança
                    setTurnRightRadians(e.getBearingRadians());
                    setAhead(e.getDistance() + 10); // +10 para garantir que ele cole no alvo
                }
            } else {
                enemyClose = false; // Retoma a movimentação aleatória
            }

                // ==========================================
                // MIRA PREDITIVA (Linear Targeting)
                // ==========================================
                double absoluteBearing = getHeadingRadians() + e.getBearingRadians();
                double enemyX = getX() + e.getDistance() * Math.sin(absoluteBearing);
                double enemyY = getY() + e.getDistance() * Math.cos(absoluteBearing);
                
                double bulletSpeed = 20 - (3 * firePower);
                double time = e.getDistance() / bulletSpeed;
                
                double futureX = enemyX + Math.sin(e.getHeadingRadians()) * e.getVelocity() * time;
                double futureY = enemyY + Math.cos(e.getHeadingRadians()) * e.getVelocity() * time;
                
                double absoluteBearingToFuture = Math.atan2(futureX - getX(), futureY - getY());
                gunTurn = Utils.normalRelativeAngle(absoluteBearingToFuture - getGunHeadingRadians());
                
            // Vira o canhão
            setTurnGunRightRadians(gunTurn);

            // Atira se a mira estiver alinhada (tolerância de ângulo)
            if (Math.abs(gunTurn) < 0.2) {
                setFire(firePower);
            }
        }
    }

    public void onHitWall(HitWallEvent e) {
        if (lowHealthMode) {
            // Foge seguindo a parede pela esquerda
            setTurnLeft(90 - e.getBearing());
            setAhead(2000); 
        } else {
            // Se esbarrar na parede na movimentação aleatória, recua
            setBack(50);
            setTurnRight(90);
        }
    }

    public void onRobotDeath(RobotDeathEvent e) {
        // Limpa a memória se o alvo atual for destruído
        if (e.getName().equals(trackName)) {
            trackName = null;
            closestDistance = 10000;
            enemyClose = false; // Volta a se mover aleatoriamente
        }
    }
}