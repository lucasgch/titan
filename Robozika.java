java
import robocode.*;
import java.awt.Color;

public class Robozika extends AdvancedRobot {
    double energiaInimiga = 100.0; // Armazena a última energia vista do inimigo
    int direcaoMovimento = 1;      // 1 para frente, -1 para trás

    public void run() {
        // Torna os componentes independentes para esquiva fluida
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForRobotTurn(true);

        while (true) {
            setTurnRadarRight(360); // Radar sempre girando
            execute();
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        // 1. Manter-se Perpendicular: Alinha o corpo a 90º do inimigo
        setTurnRight(e.getBearing() + 90 - (30 * direcaoMovimento));

        // 2. Detecção de Tiro: Verifica se a energia do inimigo caiu
        double quedaEnergia = energiaInimiga - e.getEnergy();
        if (quedaEnergia > 0 && quedaEnergia <= 3.0) {
            // Tiro detectado! Muda a direção e move-se rapidamente
            direcaoMovimento *= -1;
            setAhead(150 * direcaoMovimento);
        }
        
        energiaInimiga = e.getEnergy(); // Atualiza para a próxima verificação
        
        // Mantém o radar travado no inimigo (opcional para foco total em um)
        setTurnRadarRight(getHeading() - getRadarHeading() + e.getBearing());
    }

    public void onHitByBullet(HitByBulletEvent e) {
        // Reação imediata ao ser atingido para não ser um alvo estático
        setAhead(100 * direcaoMovimento);
    }

    public void onHitWall(HitWallEvent e) {
        // Inverte a direção ao tocar na parede para evitar perda contínua de energia
        direcaoMovimento *= -1;
        setAhead(100 * direcaoMovimento);
    }
}