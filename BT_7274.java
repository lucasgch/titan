Codigo completo mirror robocode

package sample;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.awt.geom.*;
import java.util.*;

/**
 * BT_7274 - Versão 50.0: MIRROR MATCH
 * KD-Tree Hyper-Reactive, Mirror Movement Anti-Surfer & ARIMA-Lite.
 */
public class BT_7274 extends AdvancedRobot {

    private static final Map<String, EnemyData> GLOBAL_CACHE = new HashMap<>();
    private static String currentTarget; 
    
    private List<Wave> myWaves = new ArrayList<>();
    private List<EnemyWave> enemyWaves = new ArrayList<>();
    
    private Point2D.Double myPos = new Point2D.Double();
    private Point2D.Double enemyPos = new Point2D.Double();
    private static double bfWidth, bfHeight;
    private long lastInversion = 0;
    private int moveDirection = 1;
    
    public enum EnemyType { SURFER, RAMMER, OSCILLATOR, DEFAULT }

    public static class EnemyData {
        int deaths = 0;
        int wins = 0;
        boolean isStaticElite = false; 
        
        KDTree gunTree = new KDTree();
        double[] surfingStats = new double[101];
        
        LinkedList<Double> velHistory = new LinkedList<>();
        
        double lastV = 0, lastE = 100, lastHeading = 0;
        int dirChanges = 0; long stopTicks = 0, lastReverseTime = 0;
        
        double avgReversalTime = 30.0; int reversalCount = 0;
        EnemyType currentStrategy = EnemyType.DEFAULT;
        
        StringBuilder moveHistory = new StringBuilder(); 
        List<Point2D.Double> posHistory = new ArrayList<>();
        
        double[][] gunWeights = new double[EnemyType.values().length][5]; 
        
        double damageDealt = 0;
        double damageTaken = 0;
        int hitShots = 0;
        int missedShots = 0;
        
        double[] correctionIndex = {1.0, 1.0, 1.0, 1.0, 1.0}; 
    }

    public static class Wave { 
        double startX, startY, bulletSpeed, armaPredictedAngle; int direction; long fireTime; 
        double[] features; String targetName; EnemyType enemyProfile;
        double[] virtualAngles = new double[5]; 
    }
    
    public static class EnemyWave { Point2D.Double fireLoc; long fireTime; double bulletSpeed, directAngle; int direction; double riskWeight; }

    public static class KDNode { double[] features; double gf; long time; double hitWeight; KDNode left, right; public KDNode(double[] f, double gf, long t, double hw) { this.features = f; this.gf = gf; this.time = t; this.hitWeight = hw; } }
    public static class KDTree {
        KDNode root; int nodes = 0;
        public void add(double[] f, double gf, long t, double hw) { if (nodes > 4000) { root = null; nodes = 0; } root = addRec(root, f, gf, t, hw, 0); nodes++; }
        private KDNode addRec(KDNode n, double[] f, double gf, long t, double hw, int d) {
            if (n == null) return new KDNode(f, gf, t, hw); int a = d % f.length;
            if (f[a] < n.features[a]) n.left = addRec(n.left, f, gf, t, hw, d + 1); else n.right = addRec(n.right, f, gf, t, hw, d + 1); return n;
        }
        public void findNearest(KDNode n, double[] t, int d, int k, PriorityQueue<Neighbor> pq) {
            if (n == null) return; double dist = 0; for (int i = 0; i < t.length; i++) dist += Math.pow(n.features[i] - t[i], 2);
            pq.add(new Neighbor(n, dist)); if (pq.size() > k) pq.poll();
            int a = d % t.length; KDNode near = (t[a] < n.features[a]) ? n.left : n.right, far = (near == n.left) ? n.right : n.left;
            findNearest(near, t, d + 1, k, pq); if (pq.size() < k || Math.pow(t[a] - n.features[a], 2) < pq.peek().distSq) findNearest(far, t, d + 1, k, pq);
        }
    }
    public static class Neighbor implements Comparable<Neighbor> { KDNode node; double distSq; Neighbor(KDNode n, double d) { node = n; distSq = d; } public int compareTo(Neighbor o) { return Double.compare(o.distSq, this.distSq); } }

    public void run() {
        bfWidth = getBattleFieldWidth(); bfHeight = getBattleFieldHeight();
        setColors(new Color(0, 20, 50), new Color(0, 150, 255), Color.WHITE); // Mirror Skin (Blue)
        setAdjustGunForRobotTurn(true); setAdjustRadarForGunTurn(true);
        while (true) { setTurnRadarRight(360); execute(); }
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        currentTarget = e.getName();
        myPos.setLocation(getX(), getY());
        double abs = getHeadingRadians() + e.getBearingRadians();
        enemyPos.setLocation(myPos.x + Math.sin(abs) * e.getDistance(), myPos.y + Math.cos(abs) * e.getDistance());
        
        EnemyData data = GLOBAL_CACHE.computeIfAbsent(e.getName(), k -> new EnemyData());

        if (data.deaths >= 3) data.isStaticElite = true;

        updateTimeSerieAndProfiling(e, data);
        analyzeEnemyStrategy(e, data);

        double drop = data.lastE - e.getEnergy();
        if (drop > 0 && drop <= 3) {
            EnemyWave ew = new EnemyWave();
            ew.fireLoc = new Point2D.Double(enemyPos.x, enemyPos.y); ew.fireTime = getTime() - 1; ew.bulletSpeed = 20 - (3 * drop);
            ew.directAngle = abs + Math.PI; ew.direction = (getVelocity() != 0) ? (int)Math.signum(getVelocity()) : 1; ew.riskWeight = drop;
            enemyWaves.add(ew);
        }

        checkVirtualWaves(data);

        boolean isLosingRound = (data.damageTaken > data.damageDealt * 1.1 && data.damageTaken > 30) || (data.missedShots > data.hitShots + 3);
        boolean isElite = data.isStaticElite || data.currentStrategy == EnemyType.OSCILLATOR || data.currentStrategy == EnemyType.SURFER;

        // Se identificarmos que é um Surfer, ativamos o Espelhamento
        if (data.currentStrategy == EnemyType.SURFER) {
            doMirrorMovement(e, data, abs);
        } else {
            doNemesisMovement(e, data, abs, isLosingRound, isElite); 
        }

        doJudgeVirtualTargeting(e, data, abs, isLosingRound, isElite);

        data.lastV = e.getVelocity(); data.lastE = e.getEnergy(); data.lastHeading = e.getHeadingRadians();
        setTurnRadarRightRadians(Utils.normalRelativeAngle(abs - getRadarHeadingRadians()) * 2);
    }

    private Point2D.Double smoothWall(double startAngle, double distance) {
        double testAngle = startAngle; double stick = 45.0; 
        Point2D.Double p = new Point2D.Double(getX() + Math.sin(testAngle)*distance, getY() + Math.cos(testAngle)*distance);
        int iters = 0;
        while ((p.x < stick || p.x > bfWidth - stick || p.y < stick || p.y > bfHeight - stick) && iters < 30) {
            testAngle += 0.15 * moveDirection; 
            p.x = getX() + Math.sin(testAngle)*distance; p.y = getY() + Math.cos(testAngle)*distance; iters++;
        }
        return p;
    }

    // --- NOVA TÁTICA ANTI-SURFER: MIRROR MOVEMENT ---
    private void doMirrorMovement(ScannedRobotEvent e, EnemyData data, double abs) {
        // Copia a direção lateral do inimigo. Se ele vai pra um lado, orbitamos pra esse mesmo lado espelhadamente.
        int enemyDir = (e.getVelocity() * Math.sin(e.getHeadingRadians() - abs) >= 0) ? 1 : -1;
        
        // Ajusta a distância de combate ideal para duelos contra Surfers (450-550)
        double distanceOffset = e.getDistance() - 500.0; 
        double orbitAngle = abs + (Math.PI / 2) * enemyDir; 
        
        // Se estiver longe, aproxima um pouco. Se perto, afasta (mantendo o espelho lateral)
        orbitAngle += (distanceOffset / 1000.0) * enemyDir;
        
        Point2D.Double p = smoothWall(orbitAngle, 150);
        double turnDiff = Utils.normalRelativeAngle(Math.atan2(p.x - getX(), p.y - getY()) - getHeadingRadians());
        
        // Usamos a velocidade do inimigo para regular nossa própria velocidade de órbita
        double speedMatch = Math.max(4.0, Math.abs(e.getVelocity())) * 10;
        
        if (Math.abs(turnDiff) > Math.PI / 2) { 
            setTurnRightRadians(Utils.normalRelativeAngle(turnDiff + Math.PI)); 
            setAhead(-speedMatch); 
        } else { 
            setTurnRightRadians(turnDiff); 
            setAhead(speedMatch); 
        }
    }

    private void doNemesisMovement(ScannedRobotEvent e, EnemyData data, double abs, boolean isLosingRound, boolean isElite) {
        double bestRisk = Double.POSITIVE_INFINITY; Point2D.Double bestP = new Point2D.Double(getX(), getY());
        
        double optimalDist;
        if (data.currentStrategy == EnemyType.RAMMER) optimalDist = (data.deaths > 0 || isLosingRound) ? 800.0 : 500.0;
        else if (isElite) optimalDist = 600.0; 
        else if (isLosingRound) optimalDist = 450.0; 
        else optimalDist = 140.0; 
        
        for (double i = 0; i < 2 * Math.PI; i += Math.PI / 16) {
            Point2D.Double p = smoothWall(i, (data.currentStrategy == EnemyType.RAMMER ? 250 : 180));
            double risk = Math.abs(p.distance(enemyPos) - optimalDist) * 12.0;
            if (data.currentStrategy == EnemyType.RAMMER && p.distance(enemyPos) < 300) risk += 500000;
            
            double angDiff = Math.abs(Utils.normalRelativeAngle(Math.atan2(p.x - enemyPos.x, p.y - enemyPos.y) - abs));
            if (angDiff < 0.6 || angDiff > 2.5) risk += 50000; 

            for (EnemyWave ew : enemyWaves) {
                double timeToImpact = (p.distance(ew.fireLoc) - (getTime() - ew.fireTime) * ew.bulletSpeed) / ew.bulletSpeed;
                if (timeToImpact < -3) continue; 
                double gf = Utils.normalRelativeAngle(Math.atan2(p.x-ew.fireLoc.x, p.y-ew.fireLoc.y)-ew.directAngle)/Math.asin(8.0/ew.bulletSpeed)*ew.direction;
                risk += (data.surfingStats[Math.max(0, Math.min(100, (int)Math.round(gf*50+50)))] * ew.riskWeight * 10000) / Math.max(1, Math.abs(timeToImpact - (p.distance(myPos)/8.0))); 
            }
            risk += Math.random() * 30.0;
            if (risk < bestRisk) { bestRisk = risk; bestP = p; }
        }
        
        double turnDiff = Utils.normalRelativeAngle(Math.atan2(bestP.x - getX(), bestP.y - getY()) - getHeadingRadians());
        if (Math.abs(turnDiff) > Math.PI / 2) { setTurnRightRadians(Utils.normalRelativeAngle(turnDiff + Math.PI)); setAhead(-150); } 
        else { setTurnRightRadians(turnDiff); setAhead(150); }
        if (Math.abs(getDistanceRemaining()) < 5 && getTime() - lastInversion > 15) { moveDirection *= -1; lastInversion = getTime(); }
    }

    private void updateTimeSerieAndProfiling(ScannedRobotEvent e, EnemyData data) {
        data.velHistory.addFirst(e.getVelocity());
        if (data.velHistory.size() > 5) data.velHistory.removeLast();

        if (Math.signum(e.getVelocity()) != Math.signum(data.lastV) && Math.abs(e.getVelocity()) > 0) {
            data.avgReversalTime = (data.avgReversalTime * data.reversalCount + (getTime() - data.lastReverseTime)) / (++data.reversalCount);
            data.lastReverseTime = getTime();
        }
        char symbol = (char)(((int)(e.getVelocity() - data.lastV + 8) << 8) | (int)(Math.toDegrees(Utils.normalRelativeAngle(e.getHeadingRadians() - data.lastHeading)) + 10));
        data.moveHistory.append(symbol); data.posHistory.add(new Point2D.Double(enemyPos.x, enemyPos.y));
        if (data.moveHistory.length() > 3000) { data.moveHistory.delete(0, 500); data.posHistory.subList(0, 500).clear(); }
    }

    private void analyzeEnemyStrategy(ScannedRobotEvent e, EnemyData data) {
        if (e.getDistance() < 250 && Math.abs(e.getVelocity()) > 4) {
            data.currentStrategy = EnemyType.RAMMER;
        } else if (data.avgReversalTime > 5 && data.avgReversalTime < 45 && data.reversalCount > 5) {
            data.currentStrategy = EnemyType.OSCILLATOR;
        } else {
            // Trava na rotina Surfer se ele se mantém em distância média/longa e tem flutuações de velocidade complexas
            data.currentStrategy = EnemyType.SURFER;
        }
    }

    private void checkVirtualWaves(EnemyData data) {
        Iterator<Wave> it = myWaves.iterator();
        while (it.hasNext()) {
            Wave w = it.next();
            double distTraveled = (getTime() - w.fireTime) * w.bulletSpeed;
            if (distTraveled > Point2D.distance(w.startX, w.startY, enemyPos.x, enemyPos.y) + 50) {
                double actualAngle = Math.atan2(enemyPos.x - w.startX, enemyPos.y - w.startY);
                double botWidthRadius = Math.atan(20.0 / Point2D.distance(w.startX, w.startY, enemyPos.x, enemyPos.y)); 
                
                for (int i = 0; i < 5; i++) {
                    double missMargin = Math.abs(Utils.normalRelativeAngle(w.virtualAngles[i] - actualAngle));
                    if (missMargin <= botWidthRadius) {
                        data.gunWeights[w.enemyProfile.ordinal()][i] += 2.0 * data.correctionIndex[i]; 
                        data.correctionIndex[i] = Math.min(3.0, data.correctionIndex[i] + 0.15); 
                    } else {
                        data.gunWeights[w.enemyProfile.ordinal()][i] -= 1.5 / data.correctionIndex[i]; 
                        data.correctionIndex[i] = Math.max(0.1, data.correctionIndex[i] - 0.1); 
                    }
                    data.gunWeights[w.enemyProfile.ordinal()][i] *= 0.99; 
                }
                it.remove();
            }
        }
    }

    private void doJudgeVirtualTargeting(ScannedRobotEvent e, EnemyData data, double abs, boolean isLosingRound, boolean isElite) {
        double fireP = Math.min(3.0, Math.min(getEnergy() / 6.0, 600.0 / e.getDistance()));
        double bSpd = 20 - (3 * fireP); 
        int dir = (e.getVelocity() * Math.sin(e.getHeadingRadians() - abs) >= 0) ? 1 : -1;
        
        double arFeature = 0;
        if (data.velHistory.size() == 5) {
            double diff1 = data.velHistory.get(0) - data.velHistory.get(1);
            double diff2 = data.velHistory.get(1) - data.velHistory.get(2);
            arFeature = (diff1 + diff2) / 16.0; 
        }

        double[] vAngles = new double[5];
        double reversalUrgency = Math.min(1.0, (getTime() - data.lastReverseTime) / Math.max(1.0, data.avgReversalTime));
        
        double[] feats = { e.getDistance()/800.0, e.getVelocity()/8.0, Math.sin(e.getHeadingRadians() - abs), reversalUrgency, arFeature };

        boolean runComplexCalculations = isElite || isLosingRound;

        // GUN 0: Apex KDE Hiper-Reativo para Surfers
        double kdeGF = 0;
        if (runComplexCalculations && data.gunTree.nodes > 0) {
            PriorityQueue<Neighbor> pq = new PriorityQueue<>(); data.gunTree.findNearest(data.gunTree.root, feats, 0, 50, pq);
            double[] bins = new double[101]; double adaptiveSigma = 0.02 + (0.06 / Math.max(1, Math.log(data.gunTree.nodes + 1)));
            
            // Decaimento de tempo ajustado de acordo com o perfil do inimigo. Contra Surfers é muito mais rápido!
            double decayRate = (data.currentStrategy == EnemyType.SURFER) ? 300.0 : 1200.0;
            
            for (Neighbor n : pq) {
                double w = (1.0/(0.1+n.distSq)) * Math.exp(-(getTime()-n.node.time)/decayRate) * n.node.hitWeight;
                for (int i = 0; i < 101; i++) { double u = (n.node.gf - (i-50.0)/50.0)/adaptiveSigma; bins[i] += w * Math.exp(-0.5*u*u); }
            }
            int maxI1=50, maxI2=50; double maxD1=-1, maxD2=-1;
            for(int i=0;i<101;i++){ if(bins[i]>maxD1){maxD2=maxD1;maxI2=maxI1;maxD1=bins[i];maxI1=i;} else if(bins[i]>maxD2){maxD2=bins[i];maxI2=i;} }
            kdeGF = (maxD2>maxD1*0.7 && Math.abs(maxI1-maxI2)>15) ? ((maxI1+maxI2)/2.0-50.0)/50.0 : (maxI1-50.0)/50.0;
        }
        vAngles[0] = abs + (Math.max(-1.0, Math.min(1.0, kdeGF)) * Math.asin(8.0/bSpd) * dir);

        // GUN 1: Pattern Matcher
        double pmGF = 0; int matchIndex = -1;
        if (runComplexCalculations && data.moveHistory.length() > 50) {
            String hist = data.moveHistory.toString(); int matchLen = Math.min(40, hist.length() / 2);
            matchIndex = hist.lastIndexOf(hist.substring(hist.length() - matchLen), hist.length() - matchLen - 1);
        }
        if (matchIndex >= 0) {
            int futureIndex = Math.min(data.posHistory.size() - 1, matchIndex + (int)(e.getDistance() / bSpd));
            pmGF = Utils.normalRelativeAngle(Math.atan2(data.posHistory.get(futureIndex).x - getX(), data.posHistory.get(futureIndex).y - getY()) - abs) / Math.asin(8.0/bSpd) * dir;
        }
        vAngles[1] = abs + (Math.max(-1.0, Math.min(1.0, pmGF)) * Math.asin(8.0/bSpd) * dir);

        // GUN 2, 3, 4: Geometric & Head-On
        double eHeading = e.getHeadingRadians(); double eHeadingChange = eHeading - data.lastHeading;
        vAngles[2] = abs + Math.asin((e.getVelocity() * Math.sin(eHeading - abs)) / bSpd) + (eHeadingChange * (e.getDistance() / bSpd) * 0.5);
        vAngles[3] = abs + Math.asin((e.getVelocity() * Math.sin(eHeading - abs)) / bSpd);
        vAngles[4] = abs;

        int bestGun = 0; double bestScore = -Double.MAX_VALUE;
        int profileIdx = data.currentStrategy.ordinal();
        for (int i = 0; i < 5; i++) {
            if (!runComplexCalculations && (i == 0 || i == 1)) continue;
            if (data.gunWeights[profileIdx][i] > bestScore) { bestScore = data.gunWeights[profileIdx][i]; bestGun = i; }
        }

        double finalAimAngle = vAngles[bestGun];
        setTurnGunRightRadians(Utils.normalRelativeAngle(finalAimAngle - getGunHeadingRadians()));
        
        double dynamicFireMargin = Math.max(2.5, Math.toDegrees(Math.atan(20.0 / Math.max(50.0, e.getDistance()))));
        
        if (getGunHeat() == 0 && getEnergy() > 0.1 && fireP > 0 && Math.abs(getGunTurnRemaining()) < dynamicFireMargin) {
            setFire(fireP); 
            Wave w = new Wave(); w.fireTime = getTime(); w.bulletSpeed = bSpd; w.startX = getX(); w.startY = getY(); 
            w.armaPredictedAngle = abs; w.direction = dir; w.targetName = e.getName(); w.features = feats;
            w.enemyProfile = data.currentStrategy; w.virtualAngles = vAngles; 
            myWaves.add(w);
        }
    }

    public void onDeath(DeathEvent e) {
        if (currentTarget != null) GLOBAL_CACHE.computeIfAbsent(currentTarget, k -> new EnemyData()).deaths++;
    }

    public void onWin(WinEvent e) {
        if (currentTarget != null) GLOBAL_CACHE.computeIfAbsent(currentTarget, k -> new EnemyData()).wins++;
    }

    public void onHitByBullet(HitByBulletEvent e) {
        EnemyData d = GLOBAL_CACHE.get(e.getName());
        if (d != null) {
            d.damageTaken += e.getBullet().getPower() * 4 + Math.max(0, e.getBullet().getPower() - 1) * 2;
            if (!enemyWaves.isEmpty()) {
                EnemyWave best = null; double minD = 100;
                for (EnemyWave ew : enemyWaves) { double diff = Math.abs(ew.bulletSpeed - e.getBullet().getVelocity()); if (diff < minD) { minD = diff; best = ew; } }
                if (best != null) d.surfingStats[Math.max(0, Math.min(100, (int)Math.round(Utils.normalRelativeAngle(Math.atan2(getX()-best.fireLoc.x, getY()-best.fireLoc.y)-best.directAngle)/Math.asin(8.0/best.bulletSpeed)*best.direction*50+50)))] += 3.0;
            }
        }
    }

    public void onBulletHit(BulletHitEvent e) { 
        EnemyData d = GLOBAL_CACHE.get(e.getName());
        if (d != null) { d.damageDealt += e.getBullet().getPower() * 4 + Math.max(0, e.getBullet().getPower() - 1) * 2; d.hitShots++; }
        processKDEHit(e.getName(), e.getBullet(), 5.0); 
    }
    
    public void onBulletHitBullet(BulletHitBulletEvent e) { processKDEHit(e.getHitBullet().getName(), e.getBullet(), 1.0); }
    
    public void onBulletMissed(BulletMissedEvent e) { 
        if (currentTarget != null) GLOBAL_CACHE.computeIfAbsent(currentTarget, k -> new EnemyData()).missedShots++;
        processKDEHit(null, e.getBullet(), 0.5); 
    }

    private void processKDEHit(String enemyName, Bullet b, double weight) {
        for (int i = 0; i < myWaves.size(); i++) {
            Wave w = myWaves.get(i);
            if ((enemyName == null || w.targetName.equals(enemyName)) && Math.abs(w.bulletSpeed - b.getVelocity()) < 0.1) {
                double gf = Utils.normalRelativeAngle(Math.atan2(b.getX()-w.startX, b.getY()-w.startY)-w.armaPredictedAngle)/Math.asin(8.0/w.bulletSpeed)*w.direction;
                GLOBAL_CACHE.computeIfAbsent(w.targetName, k -> new EnemyData()).gunTree.add(w.features, gf, getTime(), weight); 
                break; 
            }
        }
    }
}

