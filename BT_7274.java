package betinha;
import robocode.*;

import robocode.AdvancedRobot;
import robocode.HitWallEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;
import robocode.util.Utils;
import java.awt.Color;

/**
 * BT_7274 - "Protocolo 3: Proteger o Piloto"
 * Robô otimizado com movimentação aleatória, rastreio do alvo mais próximo
 * e fuga contornando a parede em situações críticas de vida.
 */
public class BT_7274 extends AdvancedRobot {

    String trackName = null;
    double closestDistance = 10000;
    long lastScanTime = 0;
    boolean lowHealthMode = false;

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
            if (getEnergy() <= 5 && !lowHealthMode) {
                lowHealthMode = true;
                setAhead(2000); // Força o robô a correr reto até achar uma parede
            }

            // Gira o radar continuamente
            setTurnRadarRight(360);

            // Movimentação Aleatória se a vida estiver acima de 10
            if (!lowHealthMode) {
                if (getDistanceRemaining() == 0 && getTurnRemaining() == 0) {
                    setAhead((Math.random() * 400) - 150);
                    setTurnRight((Math.random() * 220) - 60);
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

            if (lowHealthMode) {
                
                // 1. Descobrir a posição (X, Y) exata do inimigo agora
                double absoluteBearing = getHeadingRadians() + e.getBearingRadians();
                double enemyX = getX() + e.getDistance() * Math.sin(absoluteBearing);
                double enemyY = getY() + e.getDistance() * Math.cos(absoluteBearing);
                
                // 2. Calcular o tempo de viagem da bala
                double bulletSpeed = 20 - (3 * firePower);
                double time = e.getDistance() / bulletSpeed;
                
                // 3. Prever a posição futura (X, Y) baseada na velocidade do inimigo
                double futureX = enemyX + Math.sin(e.getHeadingRadians()) * e.getVelocity() * time;
                double futureY = enemyY + Math.cos(e.getHeadingRadians()) * e.getVelocity() * time;
                
                // 4. Calcular o ângulo para essa posição futura
                double absoluteBearingToFuture = Math.atan2(futureX - getX(), futureY - getY());
                
                // 5. Determinar o quanto o canhão precisa virar
                gunTurn = Utils.normalRelativeAngle(absoluteBearingToFuture - getGunHeadingRadians());
                
            } else {
                // ==========================================
                // MIRA DIRETA (Padrão)
                // ==========================================
                double absoluteBearing = getHeadingRadians() + e.getBearingRadians();
                gunTurn = Utils.normalRelativeAngle(absoluteBearing - getGunHeadingRadians());
            }
            
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
        }
    }
}