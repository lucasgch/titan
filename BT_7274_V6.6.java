package titan;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;

import robocode.*;
import robocode.util.Utils;

/**
 * BT-7274 (Versão Elite - DEFINITIVA E INDEPENDENTE)
 * Estratégia Híbrida MIRA EXTREMA + Smart Fallback + Wave Surfing
 * * MOTOR SHADOW INTEGRADO + PASSIVE BULLET SHADOW.
 * * NOVO: Predição cinemática isolada nativamente (sem dependência de kc.mega).
 * * NOVO: Override -> Se o robô não for Surfer, ele NÃO usa GF e prioriza a arma Auxiliar (0).
 * * NOVO OVERRIDE: Se for Surfer, pula avaliação, força GunWave GF (8) e salva para a partida.
 * * NOVO: Simulação visual de Shadow Waves e Predição de Target no Paint.
 * * FIX: Variável de cooldown virtual (tempoArmaVirtual_1v1) restaurada.
 * * FIX: Point2D.Double corrigido na classe Onda.
 * * REFINAMENTO: Detecção de Surfers aprimorada (não depende mais do saldo de erros de tiro).
 * * NOVO OVERRIDE MRM: Em 1v1 contra não-surfers, desativa Wave Surfing e usa MRM agressivo x3.
 * * NOVO: Memória Persistente Estática - O robô salva e restaura o perfil de cada inimigo entre rounds.
 */
public class BT_7274 extends AdvancedRobot {
    
    // =========================================================
    // CONSTANTES GLOBAIS OTIMIZADAS E VARIÁVEIS DE ESTADO
    // =========================================================
    private final int QUANTIDADE_PONTOS_PREVISTOS = 150;
    private final double MARGEM_PAREDE = 22.5; 
    private Random aleatorio = new Random();

    private double potenciaTiroCorrente = 3; 
    private double direcaoLateral = 1;       
    private double velocidadeInimigoAnterior = 0; 
    
    HashMap<String, Robo> listaInimigos = new HashMap<>();
    List<TiroInimigo> tirosSuspeitos = new ArrayList<>();
    
    Robo meuRobo = new Robo(); 
    Robo alvo;                 
    
    private int totalTirosDisparados = 0;
    private int totalTirosAcertados = 0;
    private int turnosPulados = 0; 
    
    private int[] disparosReaisVG = new int[10];
    private int[] acertosReaisVG = new int[10];
    private int ultimaVGEscolhida = -1; 
    private int vgAnteriorLog = -2;     
    private HashMap<Bullet, Integer> rastreioBalasVG = new HashMap<>(); 
    
    private final String[] NOMES_VG = {"Auxiliar", "ARMA", "ARIMA", "Rede Neural", "Dyn. Clustering", "Anti-Trem", "Média GF", "KNN Pesado", "GunWave GF", "Predict 1v1"};
    
    private static int roundsSemEscolha = 0; 
    private static int armaTravada = -1;     
    private static int ultimoRoundAvaliacao = -1; 
    private boolean escolheuArmaNaturalmente = false; 
    
    private static HashMap<String, Integer> derrotasSeguidas = new HashMap<>();
    
    // --- PERSISTÊNCIA DE ESTADO DO ADVERSÁRIO E ARMA SELECIONADA ENTRE ROUNDS ---
    private static HashMap<String, Integer> historicoArmaPorInimigo = new HashMap<>();
    private static HashMap<String, Boolean> historicoSurferPorInimigo = new HashMap<>();
    private static HashMap<String, Boolean> historicoBasicoPorInimigo = new HashMap<>();
    private static HashMap<String, Boolean> historicoIntermediarioPorInimigo = new HashMap<>();
    
    private List<Point2D.Double> caminhoSurfingVisualizado = new ArrayList<>();
    private Point2D.Double miraVisual = new Point2D.Double();
    private List<Onda> ondasAtivas = new java.util.concurrent.CopyOnWriteArrayList<>();
    
    private long tempoArmaVirtual_1v1 = 0;
    
    List<Point2D.Double> posicoesPossiveis = new ArrayList<>(450);
    int qtdPontosAtivos = 0;
    
    Point2D.Double pontoAlvo = new Point2D.Double(60, 60); 
    Rectangle2D.Double campoBatalha = new Rectangle2D.Double(); 
    
    int tempoInativo = 30; 
    boolean modoFuga = false; 
    private Movimento_1VS1 movimento1VS1; 
    
    private double energiaInimigoAnterior_1v1 = 100;
    private int[] estatisticasSurfing = new int[47]; 
    private int[] estatisticasSurfing1v1 = new int[47]; 
    ArrayList<OndaInimiga> ondasInimigas = new ArrayList<>(); 
    private boolean habilitarSurfing = true;
    
    private int inimigoTicksParado_1v1 = 0;
    
    private double confiancaSurfing = 1.0;
    private double confiancaMRM = 1.0;
    double ultimoRiscoSurfingAvaliado = 0;
    double ultimoRiscoMRMAvaliado = 0;
    double riscoSurfingAlvoAtual = 0;
    double riscoMRMAlvoAtual = 0;

    private static final int INDICES_DISTANCIA = 6;
    private static final int INDICES_VELOCIDADE = 6;
    private static final int BINS = 47; 
    private static final int BIN_CENTRAL = (BINS - 1) / 2; 
    private static final double ANGULO_ESCAPE_MAXIMO = 0.7; 
    private static final double LARGURA_BIN = ANGULO_ESCAPE_MAXIMO / (double) BIN_CENTRAL; 
    
    private final int[][][][] buffersEstatisticos = new int[INDICES_DISTANCIA][INDICES_VELOCIDADE][INDICES_VELOCIDADE][BINS];
    
    public BT_7274() {
        movimento1VS1 = new Movimento_1VS1(this);
        for (int i = 0; i < 450; i++) {
            posicoesPossiveis.add(new Point2D.Double());
        }
    }

    // =========================================================
    // MOTOR DE FÍSICA ANALÍTICA (SUBSTITUTO INDEPENDENTE DO PREDICT)
    // =========================================================
    public static class PredictFisica {
        public static double getNextVelocity(double v, int d) {
            if (d == 0) return v - Math.signum(v) * Math.min(decel(Math.abs(v)), Math.abs(v));
            return Utilitario.limitar(v + d * (Math.signum(v) * d < 0 ? decel(Math.abs(v)) : 1), -8, 8);
        }
        public static double decel(double speed) { return Utilitario.limitar(1 + speed / 2.0, 1.0, 2.0); }
        public static double getMaxTurn(double v) { return Math.PI / 18.0 - Math.abs(v) * Math.PI / 240.0; }
        public static double getTurnIncrement(double t, double v) { 
            double max = getMaxTurn(v); 
            return Utilitario.limitar(t, -max, max); 
        }
    }

    public double[] calcularLimitesEscape(Point2D.Double fonte, double alvoX, double alvoY, double alvoHeading, double alvoVel, double velocidadeBala) {
        double maxEsq = simularEscape(fonte, alvoX, alvoY, alvoHeading, alvoVel, velocidadeBala, -1);
        double maxDir = simularEscape(fonte, alvoX, alvoY, alvoHeading, alvoVel, velocidadeBala, 1);
        return new double[]{ maxEsq, maxDir };
    }

    private double simularEscape(Point2D.Double fonte, double alvoX, double alvoY, double alvoHeading, double alvoVel, double velocidadeBala, int direcaoAceleracao) {
        double simX = alvoX, simY = alvoY, simVel = alvoVel, simHeading = alvoHeading, raioBala = 0;
        double campoLargura = getBattleFieldWidth(), campoAltura = getBattleFieldHeight();
        double anguloOriginal = Math.atan2(alvoX - fonte.x, alvoY - fonte.y);
        double maiorDistanciaAngulo = 0;
        int ticks = 0;

        while (raioBala < fonte.distance(simX, simY) && ticks < 150) {
            raioBala += velocidadeBala;
            simVel = PredictFisica.getNextVelocity(simVel, direcaoAceleracao);
            
            double bearingAtual = Math.atan2(simX - fonte.x, simY - fonte.y);
            double headingDesejado = bearingAtual + (direcaoAceleracao * Math.PI / 2.0);
            double turn = Utils.normalRelativeAngle(headingDesejado - simHeading);
            simHeading += PredictFisica.getTurnIncrement(turn, simVel);

            double testeX = simX + Math.sin(simHeading) * simVel;
            double testeY = simY + Math.cos(simHeading) * simVel;
            int tentativas = 0;
            while ((testeX < MARGEM_PAREDE || testeX > campoLargura - MARGEM_PAREDE || 
                    testeY < MARGEM_PAREDE || testeY > campoAltura - MARGEM_PAREDE) && tentativas < 60) {
                headingDesejado -= direcaoAceleracao * 0.05; 
                turn = Utils.normalRelativeAngle(headingDesejado - simHeading);
                simHeading += PredictFisica.getTurnIncrement(turn, simVel);
                testeX = simX + Math.sin(simHeading) * simVel;
                testeY = simY + Math.cos(simHeading) * simVel;
                tentativas++;
            }

            simX += Math.sin(simHeading) * simVel;
            simY += Math.cos(simHeading) * simVel;
            
            double anguloAtual = Math.atan2(simX - fonte.x, simY - fonte.y);
            double offset = Utils.normalRelativeAngle(anguloAtual - anguloOriginal);
            
            if (direcaoAceleracao == 1 && offset > maiorDistanciaAngulo) maiorDistanciaAngulo = offset;
            if (direcaoAceleracao == -1 && offset < maiorDistanciaAngulo) maiorDistanciaAngulo = offset;
            
            ticks++;
        }
        return maiorDistanciaAngulo;
    }

    public Point2D.Double obterPrevisaoPrecisa1v1(Point2D.Double fonte, double alvoX, double alvoY, double alvoHeading, double alvoVel, double velocidadeBala) {
        double absoluteBearing = Math.atan2(alvoX - fonte.x, alvoY - fonte.y);
        int direcaoOrbita = (Math.sin(alvoHeading - absoluteBearing) * alvoVel >= 0) ? 1 : -1;
        if (direcaoOrbita == 0) direcaoOrbita = 1;

        double simX = alvoX, simY = alvoY, simVel = alvoVel, simHeading = alvoHeading, raioBala = 0;
        double campoLargura = getBattleFieldWidth(), campoAltura = getBattleFieldHeight();
        int ticks = 0;

        while (raioBala < fonte.distance(simX, simY) && ticks < 150) {
            raioBala += velocidadeBala;
            simVel = PredictFisica.getNextVelocity(simVel, direcaoOrbita);
            
            double bearingAtual = Math.atan2(simX - fonte.x, simY - fonte.y);
            double headingDesejado = bearingAtual + direcaoOrbita * Math.PI / 2.0;
            double turn = Utils.normalRelativeAngle(headingDesejado - simHeading);
            simHeading += PredictFisica.getTurnIncrement(turn, simVel);

            double testeX = simX + Math.sin(simHeading) * simVel;
            double testeY = simY + Math.cos(simHeading) * simVel;
            int tentativas = 0;
            while ((testeX < MARGEM_PAREDE || testeX > campoLargura - MARGEM_PAREDE || 
                    testeY < MARGEM_PAREDE || testeY > campoAltura - MARGEM_PAREDE) && tentativas < 60) {
                headingDesejado -= direcaoOrbita * 0.05; 
                turn = Utils.normalRelativeAngle(headingDesejado - simHeading);
                simHeading += PredictFisica.getTurnIncrement(turn, simVel);
                testeX = simX + Math.sin(simHeading) * simVel;
                testeY = simY + Math.cos(simHeading) * simVel;
                tentativas++;
            }
            simX += Math.sin(simHeading) * simVel;
            simY += Math.cos(simHeading) * simVel;
            ticks++;
        }
        return new Point2D.Double(simX, simY);
    }

    // =========================================================
    // HUD VISUAL COMPLETO
    // =========================================================
    public void onPaint(Graphics2D g) {
        g.setColor(Color.WHITE);
        g.drawString("== BT-7274 STATUS ==", 10, 15);
        
        if (ultimaVGEscolhida == -1) {
            g.drawString("Arma Ativa: Coletando Dados...", 10, 30);
        } else {
            String sufixo = (!escolheuArmaNaturalmente) ? " (Forçada/Alternando)" : "";
            double hitRateAtual = totalTirosDisparados > 0 ? ((double)totalTirosAcertados / totalTirosDisparados) * 100.0 : 100.0;
            
            boolean forcarPorDerrotasHUD = (alvo != null && alvo.nome != null && derrotasSeguidas.getOrDefault(alvo.nome, 0) > 5);
            
            if (forcarPorDerrotasHUD && !escolheuArmaNaturalmente) sufixo = " (Lock Vingança: Alternando)";
            else if (totalTirosDisparados >= 15 && hitRateAtual < 10.0 && !escolheuArmaNaturalmente) sufixo = " (Lock: Precisão < 10% - Alternando)";
            else if (alvo != null && !alvo.classificadoComoSurfer && getOthers() <= 1) sufixo = " (Lock: Auxiliar Anti-NonSurfer)";
            else if (alvo != null && alvo.classificadoComoSurfer) sufixo = " (Lock: GunWave GF vs Surfer)";
            
            g.drawString("Arma Ativa: " + NOMES_VG[ultimaVGEscolhida] + sufixo, 10, 30);
        }
        
        double hitRateExibicao = totalTirosDisparados > 0 ? ((double)totalTirosAcertados / totalTirosDisparados) * 100.0 : 0.0;
        g.drawString("Hit Rate Global: " + String.format(Locale.US, "%.2f%%", hitRateExibicao), 10, 45);
        if (alvo != null && alvo.nome != null) {
            int mortes = derrotasSeguidas.getOrDefault(alvo.nome, 0);
            if (mortes > 0) g.drawString("Derrotas Seguidas: " + mortes, 10, 60);
        }

        g.setColor(new Color(255, 200, 0)); 
        g.drawString("== TELEMETRIA MOVIMENTO ==", 10, 85);
        g.drawString("Modo Atual: " + (modoFuga ? "FUGA (Crítico)" : "COMBATE (Ataque/Evasão)"), 10, 100);
        
        String txtPesos = String.format(Locale.US, "Confiança Motor -> Surf: %.2f | MRM: %.2f", confiancaSurfing, confiancaMRM);
        if (getOthers() <= 1 && alvo != null && alvo.classificadoComoSurfer) {
            txtPesos = "Confiança Motor -> Surf: 1.00 | MRM: 0.00 (OVERRIDE ANTI-SURFER)"; 
        } else if (getOthers() <= 1 && alvo != null && !alvo.classificadoComoSurfer) {
            txtPesos = String.format(Locale.US, "Confiança Motor -> Surf: 0.00 (OFF) | MRM: %.2f (BOOST PREDADOR)", (confiancaMRM * 3.0)); 
        } else if (getOthers() > 1) { 
            txtPesos = String.format(Locale.US, "Confiança Motor -> Surf: %.2f (BOOST MELEE) | MRM: %.2f", (confiancaSurfing * 2.5), confiancaMRM);
        }
        g.drawString(txtPesos, 10, 115);
        
        boolean ehAvancadoMemoria = (alvo != null && (!alvo.classificadoComoBasico || alvo.classificadoComoSurfer || alvo.reversoesLaterais > 3));
        String bufferUsado = (getOthers() <= 1 && ehAvancadoMemoria) ? "1v1 Dedicado (c/ Inertia)" : "Melee Geral";
        g.drawString("Buffer Surfing: " + bufferUsado, 10, 130);
        g.drawString(String.format(Locale.US, "Risco do Destino -> Surf: %.2f | MRM: %.2f", riscoSurfingAlvoAtual, riscoMRMAlvoAtual), 10, 145);

        g.setColor(new Color(100, 255, 100)); 
        g.drawString("== PRECISÃO DAS VTs USADAS ==", 10, 170);
        int yHUD = 185;
        for (int i = 0; i < 10; i++) {
            double vgRate = disparosReaisVG[i] > 0 ? ((double)acertosReaisVG[i] / disparosReaisVG[i]) * 100.0 : 0.0;
            g.drawString(String.format(Locale.US, "[%s] %.1f%% (%d/%d)", NOMES_VG[i], vgRate, acertosReaisVG[i], disparosReaisVG[i]), 10, yHUD);
            yHUD += 15;
        }

        if (miraVisual != null && miraVisual.x > 0 && miraVisual.y > 0) {
            g.setColor(new Color(255, 50, 100, 200)); 
            g.drawLine((int)meuRobo.x, (int)meuRobo.y, (int)miraVisual.x, (int)miraVisual.y);
            g.fillOval((int)miraVisual.x - 5, (int)miraVisual.y - 5, 10, 10);
            
            String nomeArmaVis = ultimaVGEscolhida >= 0 ? NOMES_VG[ultimaVGEscolhida] : "Desconhecida";
            g.drawString("Alvo Calculado: " + nomeArmaVis, (int)miraVisual.x + 10, (int)miraVisual.y);
        }

        g.setColor(new Color(0, 255, 255, 180));
        for (Onda o : ondasAtivas) {
            if (o.posicaoCanhao == null) continue;
            double bx = o.posicaoCanhao.x + Math.sin(o.angulo + o.offsetEscolhido) * o.distanciaPercorrida;
            double by = o.posicaoCanhao.y + Math.cos(o.angulo + o.offsetEscolhido) * o.distanciaPercorrida;
            g.fillOval((int)bx - 4, (int)by - 4, 8, 8);
            
            int raio = (int) o.distanciaPercorrida;
            g.setColor(new Color(0, 255, 255, 50)); 
            g.drawOval((int)o.posicaoCanhao.x - raio, (int)o.posicaoCanhao.y - raio, raio * 2, raio * 2);
            g.setColor(new Color(0, 255, 255, 180)); 
        }

        if (caminhoSurfingVisualizado != null && !caminhoSurfingVisualizado.isEmpty()) {
            g.setColor(new Color(255, 50, 50, 180)); 
            for (int i = 0; i < caminhoSurfingVisualizado.size(); i++) {
                Point2D.Double pt = caminhoSurfingVisualizado.get(i);
                g.fillOval((int)pt.x - 2, (int)pt.y - 2, 4, 4);
                if (i > 0) {
                    Point2D.Double ptAnt = caminhoSurfingVisualizado.get(i - 1);
                    g.drawLine((int)ptAnt.x, (int)ptAnt.y, (int)pt.x, (int)pt.y);
                }
            }
            Point2D.Double ultimoPt = caminhoSurfingVisualizado.get(caminhoSurfingVisualizado.size() - 1);
            g.drawString("Path Visual", (int)ultimoPt.x + 10, (int)ultimoPt.y);
        }

        if (pontoAlvo != null && pontoAlvo.x > 0 && pontoAlvo.y > 0) {
            g.setColor(new Color(0, 255, 255, 150)); 
            g.drawOval((int)pontoAlvo.x - 15, (int)pontoAlvo.y - 15, 30, 30);
            g.drawLine((int)meuRobo.x, (int)meuRobo.y, (int)pontoAlvo.x, (int)pontoAlvo.y);
            g.drawString("Destino (MRM)", (int)pontoAlvo.x + 20, (int)pontoAlvo.y);
        }
        
        // RENDERIZAÇÃO DAS SHADOW WAVES
        try {
            int totalOndas = ondasInimigas.size();
            for (int i = 0; i < totalOndas; i++) {
                if (i >= ondasInimigas.size()) break; 
                OndaInimiga o = ondasInimigas.get(i);
                if (o == null || o.origem == null) continue;
                
                double distPercorrida = (getTime() - o.tempoDisparo) * o.velocidadeBala;
                int raioOndaInimiga = (int) distPercorrida;
                
                g.setColor(new Color(255, 50, 50, 45));
                g.drawOval((int)o.origem.x - raioOndaInimiga, (int)o.origem.y - raioOndaInimiga, raioOndaInimiga * 2, raioOndaInimiga * 2);
                
                boolean isAvancado = (alvo != null && (!alvo.classificadoComoBasico || alvo.classificadoComoSurfer || alvo.reversoesLaterais > 3));
                int[] bufferSurf = (getOthers() <= 1 && isAvancado) ? estatisticasSurfing1v1 : estatisticasSurfing;
                double maxEscapeAngle = Math.asin(8.0 / o.velocidadeBala);
                
                for (int bin = 0; bin < OndaInimiga.BINS_SURF; bin++) {
                    double offsetAngulo = (bin - OndaInimiga.BIN_CENTRO) * (o.direcaoLateral * (maxEscapeAngle / OndaInimiga.BIN_CENTRO));
                    double anguloBin = o.anguloDireto + offsetAngulo;
                    
                    double bx = o.origem.x + Math.sin(anguloBin) * distPercorrida;
                    double by = o.origem.y + Math.cos(anguloBin) * distPercorrida;
                    
                    if (bufferSurf != null && bin < bufferSurf.length && bufferSurf[bin] == 0) {
                        g.setColor(new Color(0, 255, 100, 50));
                        g.fillOval((int)bx - 2, (int)by - 2, 4, 4);
                    } else if (bufferSurf != null && bin < bufferSurf.length) {
                        int alphaRisco = Math.min(220, 45 + (bufferSurf[bin] * 12));
                        g.setColor(new Color(255, 0, 50, alphaRisco));
                        g.fillOval((int)bx - 3, (int)by - 3, 6, 6);
                    }
                }
            }
        } catch (Exception e) {}

        // PREDIÇÃO DA POSIÇÃO FUTURA DO ADVERSÁRIO
        if (alvo != null && alvo.vivo) {
            double preverX = alvo.x;
            double preverY = alvo.y;
            double direcaoSimulada = alvo.direcao;
            double deltaDir = alvo.direcao - alvo.ultimaDirecao;
            double velSimulada = alvo.velocidade;
            int maxPred = 20; 
            
            g.setColor(new Color(255, 165, 0, 150)); 
            for (int i = 0; i < maxPred; i++) {
                direcaoSimulada += deltaDir;
                preverX += Math.sin(direcaoSimulada) * velSimulada;
                preverY += Math.cos(direcaoSimulada) * velSimulada;
                
                preverX = Utilitario.limitar(preverX, MARGEM_PAREDE, campoBatalha.width - MARGEM_PAREDE);
                preverY = Utilitario.limitar(preverY, MARGEM_PAREDE, campoBatalha.height - MARGEM_PAREDE);
                
                g.fillOval((int)preverX - 2, (int)preverY - 2, 4, 4);
            }
            g.drawRect((int)preverX - 10, (int)preverY - 10, 20, 20);
            g.drawString("Alvo Simulado", (int)preverX + 12, (int)preverY + 5);
        }
    }

    // =========================================================
    // CLASSES AUXILIARES (Estruturas de Dados Customizadas)
    // =========================================================
    class Robo extends Point2D.Double {
        public long tempoVarredura; 
        public boolean vivo = true;
        public double energia;
        public String nome;
        public double anguloCanhaoRadianos;
        public double anguloAbsolutoRadianos;
        public double velocidade;
        public double direcao;
        public double ultimaDirecao;
        public double pontuacaoDisparo; 
        public double distancia; 
        
        public double fatorAmeaca = 1.0; 
        public double energiaAnterior = 100;
        public double agressividade = 0.0; 
        
        public double saldoPrecisao = 0.0; 
        public double fatorSurf = 0.0; 
        public int reversoesLaterais = 0; 
        public double ultimaVelocidadeLateral = 0.0;
        public boolean classificadoComoSurfer = false; 
        public boolean classificadoComoBasico = false; 
        public boolean classificadoComoIntermediario = false; 
        
        public int ticksParado = 0; 
        public int ticksDesdeReversao = 0;
        
        public double pesoAprendizadoDinâmico = 1.0;
        
        public LinkedList<java.lang.Double> historicoVelocidade = new LinkedList<>();
        public LinkedList<java.lang.Double> historicoDeltaDirecao = new LinkedList<>();
        
        public double[] acertosVirtualGuns = new double[10];
        
        public List<double[]> historicoKNN_features = new ArrayList<>();
        public List<Integer> historicoKNN_bins = new ArrayList<>();
    }
    
    class TiroInimigo {
        public Point2D.Double origem;
        public double velocidade;
        public double angulo;
        public long tempoDisparo;
    }
    
    public static class Utilitario {
        static double limitar(double valor, double min, double max) {
            return Math.max(min, Math.min(max, valor));
        }
        
        static double aleatorioEntre(double min, double max) {
            return min + Math.random() * (max - min);
        }
        
        static Point2D projetar(Point2D origem, double angulo, double distancia) {
            return new Point2D.Double(
                origem.getX() + Math.sin(angulo) * distancia,
                origem.getY() + Math.cos(angulo) * distancia
            );
        }
        
        static double anguloAbsoluto(Point2D origem, Point2D alvo) {
            return Math.atan2(alvo.getX() - origem.getX(), alvo.getY() - origem.getY());
        }
        
        static int sinal(double v) {
            return v < 0 ? -1 : 1;
        }
    }

    // =========================================================
    // VISIT COUNT SURFING (SISTEMA DE DEFESA WAVE SURFING)
    // =========================================================
    class OndaInimiga {
        Point2D.Double origem;
        long tempoDisparo;
        double velocidadeBala;
        double anguloDireto; 
        double direcaoLateral;
        
        static final int BINS_SURF = 47;
        static final int BIN_CENTRO = 23;

        public boolean checarAcerto(double posX, double posY, long tempoAtual) {
            double distanciaPercorrida = (tempoAtual - tempoDisparo) * velocidadeBala;
            if (distanciaPercorrida > Point2D.distance(origem.x, origem.y, posX, posY) - 18) {
                int bin = obterBin(posX, posY); 
                estatisticasSurfing[bin]++; 
                
                boolean ehAvancadoChecagem = (alvo != null && (!alvo.classificadoComoBasico || alvo.classificadoComoSurfer || alvo.reversoesLaterais > 3));
                if (getOthers() <= 1 && ehAvancadoChecagem) {
                    estatisticasSurfing1v1[bin]++;
                }
                return true; 
            }
            return false;
        }

        public int obterBin(double alvoX, double alvoY) {
            double anguloDesejado = Math.atan2(alvoX - origem.x, alvoY - origem.y);
            double offset = Utils.normalRelativeAngle(anguloDesejado - anguloDireto);
            double maxEscapeAngle = Math.asin(8.0 / velocidadeBala);
            int bin = (int) Math.round((offset / (direcaoLateral * (maxEscapeAngle / BIN_CENTRO))) + BIN_CENTRO);
            return (int) Utilitario.limitar(bin, 0, BINS_SURF - 1);
        }
    }

    public void executarSurfing() {
        if (ondasInimigas.isEmpty()) return;
        
        if (getOthers() <= 1 && alvo != null && !alvo.classificadoComoSurfer) {
            return; 
        }

        meuRobo.x = getX();
        meuRobo.y = getY();
        meuRobo.direcao = getHeadingRadians();
        meuRobo.velocidade = getVelocity();

        OndaInimiga ondaMaisProxima = null;
        double menorDistancia = Double.MAX_VALUE;

        for (int i = 0; i < ondasInimigas.size(); i++) {
            OndaInimiga onda = ondasInimigas.get(i);
            if (onda.checarAcerto(meuRobo.x, meuRobo.y, getTime())) {
                confiancaSurfing = Math.min(3.0, confiancaSurfing + 0.05);
                confiancaMRM = Math.min(3.0, confiancaMRM + 0.05);
                ondasInimigas.remove(i);
                i--;
                continue;
            }
            double distVoo = (getTime() - onda.tempoDisparo) * onda.velocidadeBala;
            double distRestante = onda.origem.distance(meuRobo) - distVoo;
            if (distRestante > 0 && distRestante < menorDistancia) {
                menorDistancia = distRestante;
                ondaMaisProxima = onda;
            }
        }

        if (ondaMaisProxima != null) {
            double riscoFrente = preverRiscoMovimento(ondaMaisProxima, 1);
            double riscoTras = preverRiscoMovimento(ondaMaisProxima, -1);
            double riscoParado = preverRiscoMovimento(ondaMaisProxima, 0);

            int direcaoSeguraVis = 1; 
            if (riscoTras < riscoFrente && riscoTras <= riscoParado) direcaoSeguraVis = -1; 
            else if (riscoParado < riscoFrente && riscoParado < riscoTras) direcaoSeguraVis = 0; 
            
            atualizarCaminhoVisual(ondaMaisProxima, direcaoSeguraVis);
        }

        boolean conflitoMotores = false; 
        if (conflitoMotores && ondaMaisProxima != null) {
            double riscoFrente = preverRiscoMovimento(ondaMaisProxima, 1);
            double riscoTras = preverRiscoMovimento(ondaMaisProxima, -1);
            double riscoParado = preverRiscoMovimento(ondaMaisProxima, 0);

            int direcaoSegura = 1;
            if (riscoTras < riscoFrente && riscoTras <= riscoParado) direcaoSegura = -1;
            else if (riscoParado < riscoFrente && riscoParado < riscoTras) direcaoSegura = 0;

            if (direcaoSegura != 0) {
                double tempoTentativa = 0;
                Point2D.Double destinoRobo = null;
                Rectangle2D areaEvasao = new Rectangle2D.Double(MARGEM_PAREDE, MARGEM_PAREDE, campoBatalha.width - MARGEM_PAREDE * 2, campoBatalha.height - MARGEM_PAREDE * 2);

                while (!areaEvasao.contains(destinoRobo = (Point2D.Double) Utilitario.projetar(
                        ondaMaisProxima.origem, 
                        Utilitario.anguloAbsoluto(ondaMaisProxima.origem, meuRobo) + (Math.PI / 2 + tempoTentativa / 100.0) * direcaoSegura, 
                        ondaMaisProxima.origem.distance(meuRobo))) 
                        && tempoTentativa < 125) {
                    tempoTentativa++;
                }
                
                double anguloEvasao = Utilitario.anguloAbsoluto(meuRobo, destinoRobo) - getHeadingRadians();
                
                if (Math.cos(anguloEvasao) < 0) {
                    anguloEvasao += Math.PI;
                    direcaoSegura *= -1;
                }
                
                setMaxVelocity(8);
                setTurnRightRadians(Utils.normalRelativeAngle(anguloEvasao));
                setAhead(100 * direcaoSegura);
            } else {
                setMaxVelocity(0); 
            }
        }
    }

    private void atualizarCaminhoVisual(OndaInimiga ondaPrimaria, int direcaoAcao) {
        // Método preservado da versão original para não remover nada
    }

    private double preverRiscoMovimento(OndaInimiga ondaPrimaria, int direcaoAcao) {
        double posPrevistaX = meuRobo.x;
        double posPrevistaY = meuRobo.y;
        double velocidadeSimulada = getVelocity();
        double direcaoSimulada = getHeadingRadians();
        double riscoPath = 0; 
        
        long tempoVoo = (long) ((ondaPrimaria.origem.distance(meuRobo) - ((getTime() - ondaPrimaria.tempoDisparo) * ondaPrimaria.velocidadeBala)) / ondaPrimaria.velocidadeBala);
        long tempoSimulado = getTime();

        boolean isAvancado = (alvo != null && (!alvo.classificadoComoBasico || alvo.classificadoComoSurfer || alvo.reversoesLaterais > 3));
        int[] bufferSurf = (getOthers() <= 1 && isAvancado) ? estatisticasSurfing1v1 : estatisticasSurfing;

        for (int i = 0; i < Math.max(1, tempoVoo); i++) {
            tempoSimulado++;
            
            double velDesejada = direcaoAcao * 8.0;
            if (velocidadeSimulada < velDesejada) {
                velocidadeSimulada += (velocidadeSimulada < 0) ? 2.0 : 1.0;
                if (velocidadeSimulada > velDesejada) velocidadeSimulada = velDesejada;
            } else if (velocidadeSimulada > velDesejada) {
                velocidadeSimulada -= (velocidadeSimulada > 0) ? 2.0 : 1.0;
                if (velocidadeSimulada < velDesejada) velocidadeSimulada = velDesejada;
            }
            
            direcaoSimulada = Math.atan2(posPrevistaX - ondaPrimaria.origem.x, posPrevistaY - ondaPrimaria.origem.y) + (Math.PI/2) * (direcaoAcao == 0 ? 1 : direcaoAcao);
            
            posPrevistaX += Math.sin(direcaoSimulada) * velocidadeSimulada;
            posPrevistaY += Math.cos(direcaoSimulada) * velocidadeSimulada;

            posPrevistaX = Utilitario.limitar(posPrevistaX, MARGEM_PAREDE, campoBatalha.width - MARGEM_PAREDE);
            posPrevistaY = Utilitario.limitar(posPrevistaY, MARGEM_PAREDE, campoBatalha.height - MARGEM_PAREDE);

            for (OndaInimiga onda : ondasInimigas) {
                if (onda == ondaPrimaria) continue; 
                
                double distOndaSimulada = (tempoSimulado - onda.tempoDisparo) * onda.velocidadeBala;
                double distRoboOnda = Point2D.distance(onda.origem.x, onda.origem.y, posPrevistaX, posPrevistaY);
                
                if (Math.abs(distOndaSimulada - distRoboOnda) <= onda.velocidadeBala) {
                    int binOnda = onda.obterBin(posPrevistaX, posPrevistaY);
                    for (int j = -2; j <= 2; j++) {
                        int binAvaliado = (int) Utilitario.limitar(binOnda + j, 0, OndaInimiga.BINS_SURF - 1);
                        riscoPath += bufferSurf[binAvaliado] * (1.0 / (Math.abs(j) + 1));
                    }
                }
            }
        }

        int binPrimario = ondaPrimaria.obterBin(posPrevistaX, posPrevistaY);
        double riscoFinal = 0;
        for (int i = -2; i <= 2; i++) { 
            int binAvaliado = (int) Utilitario.limitar(binPrimario + i, 0, OndaInimiga.BINS_SURF - 1);
            riscoFinal += bufferSurf[binAvaliado] * (1.0 / (Math.abs(i) + 1));
        }

        return riscoFinal + riscoPath; 
    }

    // =========================================================
    // LÓGICA DE MOVIMENTO 1 VS 1 (EVASÃO LEGADA FALLBACK)
    // =========================================================
    class Movimento_1VS1 {
        private static final double LARGURA_CAMPO = 800;
        private static final double ALTURA_CAMPO = 600;
        private static final double TEMPO_MAX_TENTATIVA = 125;
        private static final double AJUSTE_REVERSA = 0.421075;
        private static final double EVASAO_PADRAO = 1.2;
        private static final double AJUSTE_QUIQUE_PAREDE = 0.699484;
        
        private final AdvancedRobot robô;
        private final Rectangle2D areaDisparo = new Rectangle2D.Double(
            MARGEM_PAREDE, MARGEM_PAREDE,
            LARGURA_CAMPO - MARGEM_PAREDE * 2, ALTURA_CAMPO - MARGEM_PAREDE * 2
        );
        private double direcao = 0.4;
        
        Movimento_1VS1(AdvancedRobot _robô) {
            this.robô = _robô;
        }
        
        public void onScannedRobot(ScannedRobotEvent e) {
            Robo inimigo = new Robo();
            inimigo.anguloAbsolutoRadianos = robô.getHeadingRadians() + e.getBearingRadians();
            inimigo.distancia = e.getDistance();
            
            Point2D posicaoRobo = new Point2D.Double(robô.getX(), robô.getY());
            Point2D posicaoInimigo = Utilitario.projetar(posicaoRobo, inimigo.anguloAbsolutoRadianos, inimigo.distancia);
            Point2D destinoRobo;
            
            double tempoTentativa = 0;
            
            while (!areaDisparo.contains(destinoRobo = Utilitario.projetar(
                    posicaoInimigo, 
                    inimigo.anguloAbsolutoRadianos + Math.PI + direcao,
                    inimigo.distancia * (EVASAO_PADRAO - tempoTentativa / 100.0))) 
                    && tempoTentativa < TEMPO_MAX_TENTATIVA) {
                tempoTentativa++;
            }
                
            if ((Math.random() < (Rules.getBulletSpeed(potenciaTiroCorrente) / AJUSTE_REVERSA) / inimigo.distancia ||
                    tempoTentativa > (inimigo.distancia / Rules.getBulletSpeed(potenciaTiroCorrente) / AJUSTE_QUIQUE_PAREDE))) {
                direcao = -direcao;
            }
                
            double angulo = Utilitario.anguloAbsoluto(posicaoRobo, destinoRobo) - robô.getHeadingRadians();
            robô.setAhead(Math.cos(angulo) * 100);
            robô.setTurnRightRadians(Math.tan(angulo));
        }
    }

    // =========================================================
    // LÓGICA DE TIRO E VIRTUAL GUNS (GUESSFACTOR + KNN)
    // =========================================================
    class Onda extends Condition {
        Point2D.Double posicaoAlvo; 
        public Robo alvoOnda; 
        double potenciaTiro;
        Point2D.Double posicaoCanhao; 
        double angulo; 
        double direcaoLateralOnda;
        
        public double offsetEscolhido = 0.0;
        
        private static final double DISTANCIA_MAXIMA = 900;
        private int[] buffer; 
        private double distanciaPercorrida;
        public double pesoImpacto = 5.0; 
        
        public int binVotoAuxiliar = -1; 
        public int binVotoARMA = -1;
        public int binVotoARIMA = -1;
        public int binVotoRNA = -1;
        public int binVotoDC = -1;
        public int binVotoAntiTremidinha = -1;
        public int binVotoMedia = -1;
        public int binVotoGunWave = -1; 
        public int binVotoKNN = -1;     
        public int binVotoPredict = -1;
        
        public double[] featuresKNN; 

        private final AdvancedRobot robô;

        Onda(AdvancedRobot _robô) {
            this.robô = _robô;
        }
        
        public boolean test() {
            distanciaPercorrida += Rules.getBulletSpeed(potenciaTiro);
            
            if (distanciaPercorrida > posicaoCanhao.distance(posicaoAlvo) - MARGEM_PAREDE) {
                double anguloInimigoAtual = Math.atan2(alvoOnda.x - posicaoCanhao.x, alvoOnda.y - posicaoCanhao.y);
                double offsetBruto = Utils.normalRelativeAngle(anguloInimigoAtual - angulo);
                
                BT_7274 bot = (BT_7274) robô;
                double[] limitesEscape = bot.calcularLimitesEscape(posicaoCanhao, posicaoAlvo.x, posicaoAlvo.y, alvoOnda.direcao, alvoOnda.velocidade, Rules.getBulletSpeed(potenciaTiro));
                double meaEsquerda = Math.abs(limitesEscape[0]);
                double meaDireita = Math.abs(limitesEscape[1]);
                
                double guessFactorPreciso = 0;
                if (offsetBruto >= 0) {
                    guessFactorPreciso = (meaDireita > 0) ? (offsetBruto / meaDireita) : 0;
                } else {
                    guessFactorPreciso = (meaEsquerda > 0) ? (offsetBruto / meaEsquerda) : 0;
                }
                
                guessFactorPreciso *= direcaoLateralOnda;
                guessFactorPreciso = Math.max(-1.0, Math.min(1.0, guessFactorPreciso));
                
                int binCorreto = (int) Math.round(guessFactorPreciso * BIN_CENTRAL) + BIN_CENTRAL;
                binCorreto = Math.max(0, Math.min(BINS - 1, binCorreto));
                
                int pesoBase = (int)Math.round(10 * pesoImpacto);
                
                for (int i = 0; i < BINS; i++) {
                    double distanciaBin = Math.abs(binCorreto - i);
                    if (distanciaBin <= 5) { 
                        buffer[i] += (int) Math.round(pesoBase / (Math.pow(2, distanciaBin)));
                    }
                }
                
                if (alvoOnda != null) {
                    if (featuresKNN != null) {
                        alvoOnda.historicoKNN_features.add(featuresKNN);
                        alvoOnda.historicoKNN_bins.add(binCorreto);
                        if (alvoOnda.historicoKNN_features.size() > 30000) { 
                            alvoOnda.historicoKNN_features.remove(0); 
                            alvoOnda.historicoKNN_bins.remove(0);
                        }
                    }

                    if (binVotoAuxiliar != -1 && Math.abs(binCorreto - binVotoAuxiliar) <= 2) alvoOnda.acertosVirtualGuns[0]++;
                    if (binVotoARMA != -1 && Math.abs(binCorreto - binVotoARMA) <= 2) alvoOnda.acertosVirtualGuns[1]++;
                    if (binVotoARIMA != -1 && Math.abs(binCorreto - binVotoARIMA) <= 2) alvoOnda.acertosVirtualGuns[2]++;
                    if (binVotoRNA != -1 && Math.abs(binCorreto - binVotoRNA) <= 2) alvoOnda.acertosVirtualGuns[3]++;
                    if (binVotoDC != -1 && Math.abs(binCorreto - binVotoDC) <= 2) alvoOnda.acertosVirtualGuns[4]++;
                    if (binVotoAntiTremidinha != -1 && Math.abs(binCorreto - binVotoAntiTremidinha) <= 2) alvoOnda.acertosVirtualGuns[5]++;
                    if (binVotoMedia != -1 && Math.abs(binCorreto - binVotoMedia) <= 2) alvoOnda.acertosVirtualGuns[6]++;
                    if (binVotoKNN != -1 && Math.abs(binCorreto - binVotoKNN) <= 2) alvoOnda.acertosVirtualGuns[7]++;
                    if (binVotoGunWave != -1 && Math.abs(binCorreto - binVotoGunWave) <= 2) alvoOnda.acertosVirtualGuns[8]++;
                    if (binVotoPredict != -1 && Math.abs(binCorreto - binVotoPredict) <= 2) alvoOnda.acertosVirtualGuns[9]++;
                    
                    for (int j = 0; j < 10; j++) {
                        alvoOnda.acertosVirtualGuns[j] *= 0.95; 
                    }
                }

                BT_7274.this.ondasAtivas.remove(this); 
                robô.removeCustomEvent(this);
            }
            return false;
        }

        public void registrarMiraPredict1v1(Robo inimigo, Robo meuRobo, double velocidadeBala) {
            BT_7274 bot = (BT_7274) robô;
            Point2D.Double pontoPrevisto = bot.obterPrevisaoPrecisa1v1(
                new Point2D.Double(posicaoCanhao.x, posicaoCanhao.y), 
                inimigo.x, inimigo.y, 
                inimigo.direcao, 
                inimigo.velocidade, 
                velocidadeBala
            );
            double anguloPredict = Utilitario.anguloAbsoluto(posicaoCanhao, pontoPrevisto);
            int binPredict = (int) Math.round((Utils.normalRelativeAngle(anguloPredict - angulo) / (direcaoLateralOnda * LARGURA_BIN)) + BIN_CENTRAL);
            binVotoPredict = (int) Utilitario.limitar(binPredict, 0, BINS - 1);
        }

        public void registrarMiraKNNPesado(Robo inimigo, Robo meuRobo) {
            if (inimigo.historicoKNN_features.size() < 10 || featuresKNN == null) return;
            
            int limiteProfundidade = inimigo.classificadoComoSurfer ? inimigo.historicoKNN_features.size() : Math.min(500, inimigo.historicoKNN_features.size());
            int n = limiteProfundidade;
            
            int K = (int) Math.min(50, Math.sqrt(n)); 
            double[] topDist = new double[K];
            int[] topBins = new int[K];
            Arrays.fill(topDist, Double.MAX_VALUE);
            
            for (int i = inimigo.historicoKNN_features.size() - n; i < inimigo.historicoKNN_features.size(); i++) {
                double[] hist = inimigo.historicoKNN_features.get(i);
                double d = 0;
                double diff;
                
                diff = (hist[0] - featuresKNN[0]) / 8.0;   d += diff * diff * 2.0;   
                diff = (hist[1] - featuresKNN[1]) / 0.1;   d += diff * diff * 3.0;   
                diff = (hist[2] - featuresKNN[2]) / 1000.0; d += diff * diff * 1.0;  
                diff = (hist[3] - featuresKNN[3]) / 8.0;   d += diff * diff * 2.5;   
                diff = (hist[4] - featuresKNN[4]) / 2.0;   d += diff * diff * 1.5;   
                diff = (hist[5] - featuresKNN[5]) / 100.0;  d += diff * diff * 1.0;  
                
                if (d < topDist[K-1]) {
                    int j = K - 1;
                    while(j > 0 && d < topDist[j-1]) {
                        topDist[j] = topDist[j-1];
                        topBins[j] = topBins[j-1];
                        j--;
                    }
                    topDist[j] = d;
                    topBins[j] = inimigo.historicoKNN_bins.get(i); 
                }
            }
            
            double[] binsWeights = new double[BINS];
            for (int i = 0; i < K; i++) {
                if (topDist[i] == Double.MAX_VALUE) break;
                double weight = 1.0 / (1.0 + topDist[i]);
                binsWeights[topBins[i]] += weight;
            }
            
            int bestBin = BIN_CENTRAL;
            double maxW = -1;
            for (int i = 0; i < BINS; i++) {
                if (binsWeights[i] > maxW) {
                    maxW = binsWeights[i];
                    bestBin = i;
                }
            }
            binVotoKNN = bestBin;
        }
        
        public void registrarMirasAuxiliares(Robo inimigo, Robo meuRobo, double tempoEstimado) {
            double inimigoX = meuRobo.x + inimigo.distancia * Math.sin(inimigo.anguloAbsolutoRadianos);
            double inimigoY = meuRobo.y + inimigo.distancia * Math.cos(inimigo.anguloAbsolutoRadianos);
            
            double angulo1 = inimigo.anguloAbsolutoRadianos; 
            
            double linX = inimigoX + Math.sin(inimigo.direcao) * inimigo.velocidade * tempoEstimado;
            double linY = inimigoY + Math.cos(inimigo.direcao) * inimigo.velocidade * tempoEstimado;
            double angulo2 = Utilitario.anguloAbsoluto(meuRobo, new Point2D.Double(linX, linY));
            
            double deltaDir = inimigo.direcao - inimigo.ultimaDirecao;
            double circX, circY;
            if (Math.abs(deltaDir) < 0.00001) {
                circX = linX; circY = linY;
            } else {
                circX = inimigoX + (inimigo.velocidade / deltaDir) * (Math.cos(inimigo.direcao) - Math.cos(inimigo.direcao + deltaDir * tempoEstimado));
                circY = inimigoY + (inimigo.velocidade / deltaDir) * (Math.sin(inimigo.direcao + deltaDir * tempoEstimado) - Math.sin(inimigo.direcao));
            }
            double angulo3 = Utilitario.anguloAbsoluto(meuRobo, new Point2D.Double(circX, circY));
            
            double mediaSeno = (Math.sin(angulo1) + Math.sin(angulo2) + Math.sin(angulo3)) / 3.0;
            double mediaCosseno = (Math.cos(angulo1) + Math.cos(angulo2) + Math.cos(angulo3)) / 3.0;
            double anguloMedio = Math.atan2(mediaSeno, mediaCosseno);
            
            double offsetMedio = Utils.normalRelativeAngle(anguloMedio - angulo);
            int binMedia = (int) Math.round((offsetMedio / (direcaoLateralOnda * LARGURA_BIN)) + BIN_CENTRAL);
            
            binVotoAuxiliar = (int) Utilitario.limitar(binMedia, 0, BINS - 1);
        }

        public void registrarMirasARMA_ARIMA(Robo inimigo, Robo meuRobo, double tempoEstimado, int qtdInimigosVivos) {
            if (inimigo.historicoVelocidade.size() < 5) return; 
            
            int profundidadeMaxima = (qtdInimigosVivos > 2) ? 5 : ((qtdInimigosVivos == 2) ? 15 : 40);
            int n = Math.min(inimigo.historicoVelocidade.size(), profundidadeMaxima);
            
            double iniX = meuRobo.x + inimigo.distancia * Math.sin(inimigo.anguloAbsolutoRadianos);
            double iniY = meuRobo.y + inimigo.distancia * Math.cos(inimigo.anguloAbsolutoRadianos);
            double velBala = Rules.getBulletSpeed(this.potenciaTiro);
            
            double mu_v = 0, mu_d = 0;
            for(int i = 0; i < n; i++) {
                mu_v += inimigo.historicoVelocidade.get(i);
                mu_d += inimigo.historicoDeltaDirecao.get(i);
            }
            mu_v /= n; 
            mu_d /= n;
            
            double c0_v = 0, c1_v = 0, c0_d = 0, c1_d = 0;
            for(int i = 0; i < n - 1; i++) {
                double diff_v_i = inimigo.historicoVelocidade.get(i) - mu_v;
                double diff_v_i1 = inimigo.historicoVelocidade.get(i+1) - mu_v;
                c0_v += diff_v_i * diff_v_i;
                c1_v += diff_v_i * diff_v_i1;

                double diff_d_i = inimigo.historicoDeltaDirecao.get(i) - mu_d;
                double diff_d_i1 = inimigo.historicoDeltaDirecao.get(i+1) - mu_d;
                c0_d += diff_d_i * diff_d_i;
                c1_d += diff_d_i * diff_d_i1;
            }
            c0_v += Math.pow(inimigo.historicoVelocidade.get(n-1) - mu_v, 2);
            c0_d += Math.pow(inimigo.historicoDeltaDirecao.get(n-1) - mu_d, 2);

            double phi_v = (c0_v == 0) ? 0 : Utilitario.limitar(c1_v / c0_v, -0.99, 0.99);
            double phi_d = (c0_d == 0) ? 0 : Utilitario.limitar(c1_d / c0_d, -0.99, 0.99);

            double erro_v = inimigo.historicoVelocidade.get(0) - (mu_v + phi_v * (inimigo.historicoVelocidade.get(1) - mu_v));
            double erro_d = inimigo.historicoDeltaDirecao.get(0) - (mu_d + phi_d * (inimigo.historicoDeltaDirecao.get(1) - mu_d));
            double theta_v = 0.5; 
            double theta_d = 0.5;

            double simArmaX = iniX, simArmaY = iniY;
            double simArmaDir = inimigo.direcao;
            double simArmaVel = inimigo.velocidade;
            double simArmaDeltaDir = inimigo.direcao - inimigo.ultimaDirecao;
            int tArma = 0;
            
            while (Point2D.distance(meuRobo.x, meuRobo.y, simArmaX, simArmaY) > tArma * velBala && tArma < 150) {
                tArma++;
                simArmaVel = mu_v + phi_v * (simArmaVel - mu_v);
                simArmaDeltaDir = mu_d + phi_d * (simArmaDeltaDir - mu_d);
                if (tArma == 1) { 
                    simArmaVel += theta_v * erro_v;
                    simArmaDeltaDir += theta_d * erro_d;
                }
                
                simArmaDir += simArmaDeltaDir;
                simArmaX += Math.sin(simArmaDir) * simArmaVel;
                simArmaY += Math.cos(simArmaDir) * simArmaVel;
            }
            double anguloARMA = Utilitario.anguloAbsoluto(meuRobo, new Point2D.Double(simArmaX, simArmaY));
            int binARMA = (int) Math.round((Utils.normalRelativeAngle(anguloARMA - angulo) / (direcaoLateralOnda * LARGURA_BIN)) + BIN_CENTRAL);
            binVotoARMA = (int) Utilitario.limitar(binARMA, 0, BINS - 1);
            
            double[] diff_v = new double[n-1];
            double[] diff_d = new double[n-1];
            double mu_dv = 0, mu_dd = 0;
            
            for(int i = 0; i < n - 1; i++) {
                diff_v[i] = inimigo.historicoVelocidade.get(i) - inimigo.historicoVelocidade.get(i+1);
                diff_d[i] = inimigo.historicoDeltaDirecao.get(i) - inimigo.historicoDeltaDirecao.get(i+1);
                mu_dv += diff_v[i];
                mu_dd += diff_d[i];
            }
            if (n > 1) { mu_dv /= (n-1); mu_dd /= (n-1); }

            double c0_dv = 0, c1_dv = 0, c0_dd = 0, c1_dd = 0;
            for(int i = 0; i < n - 2; i++) {
                double d_vi = diff_v[i] - mu_dv;
                double d_vi1 = diff_v[i+1] - mu_dv;
                c0_dv += d_vi * d_vi;
                c1_dv += d_vi * d_vi1;

                double d_di = diff_d[i] - mu_dd;
                double d_di1 = diff_d[i+1] - mu_dd;
                c0_dd += d_di * d_di;
                c1_dd += d_di * d_di1;
            }
            if (n > 2) {
                c0_dv += Math.pow(diff_v[n-2] - mu_dv, 2);
                c0_dd += Math.pow(diff_d[n-2] - mu_dd, 2);
            }

            double phi_dv = (c0_dv == 0) ? 0 : Utilitario.limitar(c1_dv / c0_dv, -0.99, 0.99);
            double phi_dd = (c0_dd == 0) ? 0 : Utilitario.limitar(c1_dd / c0_dd, -0.99, 0.99);

            double erro_dv = (n > 1) ? diff_v[0] - (mu_dv + phi_dv * ((n > 2 ? diff_v[1] : 0) - mu_dv)) : 0;
            double erro_dd = (n > 1) ? diff_d[0] - (mu_dd + phi_dd * ((n > 2 ? diff_d[1] : 0) - mu_dd)) : 0;

            double simArimaX = iniX, simArimaY = iniY;
            double simArimaVel = inimigo.velocidade;
            double simArimaDir = inimigo.direcao;
            double simArimaDeltaDir = inimigo.direcao - inimigo.ultimaDirecao;
            
            double simArimaDiffVel = (n > 1) ? diff_v[0] : 0;
            double simArimaDiffDeltaDir = (n > 1) ? diff_d[0] : 0;
            int tArima = 0;
            
            while (Point2D.distance(meuRobo.x, meuRobo.y, simArimaX, simArimaY) > tArima * velBala && tArima < 150) {
                tArima++;
                
                simArimaDiffVel = mu_dv + phi_dv * (simArimaDiffVel - mu_dv);
                simArimaDiffDeltaDir = mu_dd + phi_dd * (simArimaDiffDeltaDir - mu_dd);
                if (tArima == 1) {
                    simArimaDiffVel += theta_v * erro_dv;
                    simArimaDiffDeltaDir += theta_d * erro_dd;
                }

                simArimaVel = Utilitario.limitar(simArimaVel + simArimaDiffVel, -8, 8); 
                simArimaDeltaDir += simArimaDiffDeltaDir; 
                simArimaDir += simArimaDeltaDir;
                
                simArimaX += Math.sin(simArimaDir) * simArimaVel;
                simArimaY += Math.cos(simArimaDir) * simArimaVel;
            }
            double anguloARIMA = Utilitario.anguloAbsoluto(meuRobo, new Point2D.Double(simArimaX, simArimaY));
            int binARIMA = (int) Math.round((Utils.normalRelativeAngle(anguloARIMA - angulo) / (direcaoLateralOnda * LARGURA_BIN)) + BIN_CENTRAL);
            binVotoARIMA = (int) Utilitario.limitar(binARIMA, 0, BINS - 1);
        }
        
        double offsetAnguloMaisVisitado() {
            BT_7274 bot = (BT_7274) robô;
            
            int maisVisitado = BIN_CENTRAL;
            double maiorVoto = -1; 
            
            int roundAtual = bot.getRoundNum();
            
            if (BT_7274.ultimoRoundAvaliacao != roundAtual) {
                int melhorVGDestaAvaliacao = -1;
                double maxAcertosVG = -1.0; 
                
                if (bot.alvo != null && bot.alvo.classificadoComoSurfer) {
                    // PULA avaliação de todas as outras e seta fixo
                    melhorVGDestaAvaliacao = 8;
                } else if (bot.alvo != null && bot.getOthers() <= 1) {
                    for (int j = 0; j < 10; j++) { 
                        
                        double pontuacaoAvaliada = bot.alvo.acertosVirtualGuns[j];
                        
                        boolean ehAvancado = !bot.alvo.classificadoComoBasico && !bot.alvo.classificadoComoIntermediario || bot.alvo.classificadoComoSurfer || bot.alvo.reversoesLaterais > 3;
                        
                        if (ehAvancado) {
                            if (j == 0) pontuacaoAvaliada *= 0.2; 
                            if (j == 6) pontuacaoAvaliada *= 0.5; 
                            if (j == 7) pontuacaoAvaliada *= 1.5; 
                            if (j == 8) pontuacaoAvaliada *= 1.6; 
                            if (j == 9) pontuacaoAvaliada *= 1.8; 
                            if (j == 3 || j == 4) pontuacaoAvaliada *= 1.2; 
                        } else {
                            if (j == 0) pontuacaoAvaliada *= 1.5; 
                            if (j == 1 || j == 2) pontuacaoAvaliada *= 1.2; 
                            if (j == 9) pontuacaoAvaliada *= 1.5; 
                        }
                        
                        if (pontuacaoAvaliada > maxAcertosVG) {
                            maxAcertosVG = pontuacaoAvaliada;
                            melhorVGDestaAvaliacao = j;
                        }
                    }
                }
                
                if (melhorVGDestaAvaliacao != -1) {
                    BT_7274.armaTravada = melhorVGDestaAvaliacao;
                    bot.escolheuArmaNaturalmente = true;
                } else {
                    bot.escolheuArmaNaturalmente = false;
                }
                BT_7274.ultimoRoundAvaliacao = roundAtual;
            }
            
            if (BT_7274.armaTravada != -1) {
                bot.escolheuArmaNaturalmente = true;
            }

            int melhorVG = BT_7274.armaTravada;
            int armaAlternada = (bot.getRoundNum() % 2 == 0) ? 9 : 8; 
            
            if (bot.alvo != null && bot.alvo.classificadoComoBasico && !bot.alvo.classificadoComoSurfer) {
                armaAlternada = 1; 
                melhorVG = 1;
            }
            
            double precisaoGlobalOnda = bot.totalTirosDisparados > 0 ? ((double)bot.totalTirosAcertados / bot.totalTirosDisparados) * 100.0 : 100.0;
            boolean forcarPorPrecisao = (bot.totalTirosDisparados >= 15 && precisaoGlobalOnda < 10.0);
            boolean forcarPorDerrotas = (bot.alvo != null && bot.alvo.nome != null && BT_7274.derrotasSeguidas.getOrDefault(bot.alvo.nome, 0) > 5);
            
            boolean ehAvancadoLock = !bot.alvo.classificadoComoBasico && !bot.alvo.classificadoComoIntermediario || bot.alvo.classificadoComoSurfer || bot.alvo.reversoesLaterais > 3;
            boolean surfing1v1DedicadoAtivo = (bot.getOthers() <= 1 && ehAvancadoLock);
            
            if (bot.alvo != null && bot.alvo.classificadoComoBasico && !bot.alvo.classificadoComoSurfer) {
                surfing1v1DedicadoAtivo = false;
            }
            
            if (surfing1v1DedicadoAtivo) {
                melhorVG = 8; 
                bot.escolheuArmaNaturalmente = false;
            } else if (forcarPorDerrotas) {
                melhorVG = armaAlternada; 
                bot.escolheuArmaNaturalmente = false;
            } else if (bot.alvo != null && bot.alvo.classificadoComoSurfer && (melhorVG == -1 || melhorVG < 3)) {
                if (bot.alvo.agressividade > 2.0) {
                    melhorVG = armaAlternada; 
                    bot.escolheuArmaNaturalmente = false;
                } else {
                    melhorVG = armaAlternada; 
                    bot.escolheuArmaNaturalmente = false;
                }
            } else if (forcarPorPrecisao) {
                melhorVG = armaAlternada; 
                bot.escolheuArmaNaturalmente = false;
            } else if (melhorVG == -1) { 
                if (BT_7274.roundsSemEscolha >= 4) {
                    melhorVG = armaAlternada; 
                    bot.escolheuArmaNaturalmente = false;
                }
            } else {
                bot.escolheuArmaNaturalmente = true; 
            }
            
            // --- NOVO OVERRIDE: Se 1v1 e não for surfer, usa a Auxiliar (0) ---
            if (bot.getOthers() <= 1 && bot.alvo != null && !bot.alvo.classificadoComoSurfer) {
                melhorVG = 0;
                bot.escolheuArmaNaturalmente = false;
            } 
            // --- NOVO OVERRIDE: Se for Surfer, trava na GunWave GF (8) e salva para a partida toda ---
            else if (bot.alvo != null && bot.alvo.classificadoComoSurfer) {
                melhorVG = 8;
                bot.escolheuArmaNaturalmente = false;
                BT_7274.armaTravada = 8;
            }

            bot.ultimaVGEscolhida = melhorVG;
            
            int melhorBinGunWave = BIN_CENTRAL;
            double maxBuffer = -1;
            for(int i = 0; i < BINS; i++) {
                if(buffer[i] > maxBuffer) {
                    maxBuffer = buffer[i];
                    melhorBinGunWave = i;
                }
            }
            binVotoGunWave = melhorBinGunWave;
            
            for (int i = 0; i < BINS; i++) {
                double votos = buffer[i];
                if (i == binVotoAuxiliar) votos += (10.0 * pesoImpacto); 
                
                if (bot.getOthers() <= 1 && melhorVG != -1) {
                    double superPeso = 25000.0 * pesoImpacto; 
                    if (melhorVG == 0 && i == binVotoAuxiliar) votos += superPeso;
                    if (melhorVG == 1 && i == binVotoARMA) votos += superPeso;
                    if (melhorVG == 2 && i == binVotoARIMA) votos += superPeso;
                    if (melhorVG == 3 && i == binVotoRNA) votos += superPeso;
                    if (melhorVG == 4 && i == binVotoDC) votos += superPeso;
                    if (melhorVG == 5 && i == binVotoAntiTremidinha) votos += superPeso;
                    if (melhorVG == 6 && i == binVotoMedia) votos += superPeso;
                    if (melhorVG == 7 && i == binVotoKNN) votos += superPeso;
                    if (melhorVG == 8 && i == binVotoGunWave) votos += superPeso; 
                    if (melhorVG == 9 && i == binVotoPredict) votos += superPeso; 
                }
                
                if (bot.alvo != null && bot.alvo.classificadoComoBasico) {
                    if (i == binVotoARMA) votos += (10000.0 * pesoImpacto);
                    if (i == binVotoARIMA) votos += (10000.0 * pesoImpacto);
                } else {
                    if (i == binVotoARMA) votos += (8.0 * pesoImpacto);
                    if (i == binVotoARIMA) votos += (8.0 * pesoImpacto);
                }
                
                if (votos > maiorVoto) {
                    maiorVoto = votos;
                    maisVisitado = i;
                }
            }
            return (direcaoLateralOnda * LARGURA_BIN) * (maisVisitado - BIN_CENTRAL);
        }
        
        void definirSegmentacoes(double distancia, double velocidade, double ultimaVelocidade) {
            int indiceDistancia = (int) Math.min(INDICES_DISTANCIA - 1, distancia / (DISTANCIA_MAXIMA / INDICES_DISTANCIA));
            int indiceVelocidade = (int) Math.min(INDICES_VELOCIDADE - 1, Math.abs(velocidade / 2));
            int indiceUltimaVelocidade = (int) Math.min(INDICES_VELOCIDADE - 1, Math.abs(ultimaVelocidade / 2));
            buffer = buffersEstatisticos[indiceDistancia][indiceVelocidade][indiceUltimaVelocidade];
        }
    }
    
    // =========================================================
    // ESTÉTICA E CORES 
    // =========================================================
    private void coresBT7274() {
        setColors(new Color(60, 80, 40), new Color(255, 120, 0), new Color(100, 100, 100), 
                  new Color(255, 120, 0), new Color(255, 120, 0));
    }

    // =========================================================
    // LOOP PRINCIPAL (RUN)
    // =========================================================
    public void run() {
        campoBatalha.height = getBattleFieldHeight();
        campoBatalha.width = getBattleFieldWidth();
        
        meuRobo.x = getX();
        meuRobo.y = getY();
        meuRobo.energia = getEnergy();
        
        pontoAlvo.x = meuRobo.x;
        pontoAlvo.y = meuRobo.y;
        
        alvo = new Robo();
        alvo.vivo = false;
        
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true); 
        
        if (getOthers() > 1) { 
            atualizarListaPosicoes(QUANTIDADE_PONTOS_PREVISTOS * 3); 
            setTurnRadarRightRadians(Double.POSITIVE_INFINITY); 
            
            while (true) {
                meuRobo.ultimaDirecao = meuRobo.direcao;
                meuRobo.direcao = getHeadingRadians();
                meuRobo.x = getX();
                meuRobo.y = getY();
                meuRobo.energia = getEnergy();
                meuRobo.anguloCanhaoRadianos = getGunHeadingRadians();
                
                verificarFuga(); 
                
                Iterator<Robo> iteradorInimigos = listaInimigos.values().iterator();
                while (iteradorInimigos.hasNext()) {
                    Robo r = iteradorInimigos.next();
                    if (getTime() - r.tempoVarredura > 25) {
                        r.vivo = false;
                        if (alvo.nome != null && r.nome.equals(alvo.nome))
                            alvo.vivo = false;
                    }
                }
                
                movimento(); 
                
                if (alvo.vivo) {
                    disparar(); 
                }
                execute(); 
            }
        } else { 
            direcaoLateral = 1;
            velocidadeInimigoAnterior = 0;
            
            setTurnRadarRightRadians(Double.POSITIVE_INFINITY); 
            
            while (true) {
                if (getRadarTurnRemaining() == 0.0) {
                    setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
                }
                execute();
            }
        }
    }

    // =========================================================
    // EVENTOS DO ROBÔ & PERFILAMENTO DE ESTRATÉGIA
    // =========================================================
    public void onScannedRobot(ScannedRobotEvent e) {
        coresBT7274();
        
        if (getOthers() > 1) { 
            String nomeInimigo = e.getName();
            Robo inimigo = listaInimigos.get(nomeInimigo);
            
            if (inimigo == null) {
                inimigo = new Robo();
                inimigo.nome = nomeInimigo;
                listaInimigos.put(nomeInimigo, inimigo);
                
                // RESTAURAR ESTADO SALVO DO ROUND ANTERIOR NO INÍCIO DESTE ROUND
                if (historicoArmaPorInimigo.containsKey(nomeInimigo)) {
                    this.ultimaVGEscolhida = historicoArmaPorInimigo.get(nomeInimigo);
                }
                if (historicoSurferPorInimigo.containsKey(nomeInimigo)) {
                    inimigo.classificadoComoSurfer = historicoSurferPorInimigo.get(nomeInimigo);
                }
                if (historicoBasicoPorInimigo.containsKey(nomeInimigo)) {
                    inimigo.classificadoComoBasico = historicoBasicoPorInimigo.get(nomeInimigo);
                }
                if (historicoIntermediarioPorInimigo.containsKey(nomeInimigo)) {
                    inimigo.classificadoComoIntermediario = historicoIntermediarioPorInimigo.get(nomeInimigo);
                }
            }
            
            if (alvo != null && alvo.nome != null && alvo.nome.equals(nomeInimigo) && getTime() < 5) {
                if (historicoArmaPorInimigo.containsKey(nomeInimigo)) {
                    this.ultimaVGEscolhida = historicoArmaPorInimigo.get(nomeInimigo);
                }
            }
            
            if (Math.abs(e.getVelocity()) < 1.5) {
                inimigo.ticksParado++;
            } else {
                inimigo.ticksParado = 0;
            }
            
            double quedaEnergia = inimigo.energiaAnterior - e.getEnergy();
            if (quedaEnergia > 0 && quedaEnergia <= 3) {
                inimigo.agressividade += 0.1; 
                
                TiroInimigo novoTiro = new TiroInimigo();
                novoTiro.origem = new Point2D.Double(
                    meuRobo.x + e.getDistance() * Math.sin(getHeadingRadians() + e.getBearingRadians()),
                    meuRobo.y + e.getDistance() * Math.cos(getHeadingRadians() + e.getBearingRadians())
                );
                novoTiro.velocidade = Rules.getBulletSpeed(quedaEnergia);
                novoTiro.angulo = Utilitario.anguloAbsoluto(novoTiro.origem, meuRobo);
                novoTiro.tempoDisparo = getTime();
                tirosSuspeitos.add(novoTiro); 
            }
            inimigo.energiaAnterior = e.getEnergy();
            
            inimigo.fatorAmeaca = (e.getEnergy() / Math.max(1, meuRobo.energia)) + inimigo.agressividade;
            if (e.getDistance() < 250) inimigo.fatorAmeaca *= 1.5; 

            inimigo.anguloAbsolutoRadianos = e.getBearingRadians();
            inimigo.setLocation(new Point2D.Double(
                    meuRobo.x + e.getDistance() * Math.sin(getHeadingRadians() + inimigo.anguloAbsolutoRadianos),
                    meuRobo.y + e.getDistance() * Math.cos(getHeadingRadians() + inimigo.anguloAbsolutoRadianos)));
            inimigo.ultimaDirecao = inimigo.direcao;
            inimigo.nome = e.getName();
            inimigo.energia = e.getEnergy();
            inimigo.vivo = true;
            inimigo.tempoVarredura = getTime();
            inimigo.velocidade = e.getVelocity();
            inimigo.direcao = e.getHeadingRadians();
            
            inimigo.historicoVelocidade.addFirst(inimigo.velocidade);
            inimigo.historicoDeltaDirecao.addFirst(inimigo.direcao - inimigo.ultimaDirecao);
            if (inimigo.historicoVelocidade.size() > 50) inimigo.historicoVelocidade.removeLast();
            if (inimigo.historicoDeltaDirecao.size() > 50) inimigo.historicoDeltaDirecao.removeLast();
            
            double distParedeInimigo = Math.min(
                Math.min(inimigo.x, campoBatalha.width - inimigo.x), 
                Math.min(inimigo.y, campoBatalha.height - inimigo.y)
            );
            boolean isClinger = distParedeInimigo < 60; 
            boolean isLinear = inimigo.reversoesLaterais < 2 && inimigo.historicoVelocidade.size() >= 30;
            
            // NOVO: Detecção de seguidor/perseguidor (Intermediário)
            boolean isFollower = false;
            if (inimigo.historicoVelocidade.size() > 10) {
                double anguloParaBT = Utils.normalAbsoluteAngle(getHeadingRadians() + e.getBearingRadians() + Math.PI);
                double moveDir = (inimigo.velocidade >= 0) ? inimigo.direcao : Utils.normalAbsoluteAngle(inimigo.direcao + Math.PI);
                double difAngulo = Math.abs(Utils.normalRelativeAngle(moveDir - anguloParaBT));
                
                if (difAngulo < 0.35 && Math.abs(inimigo.velocidade) > 1.0) {
                     isFollower = true;
                }
            }

            if (isClinger || isLinear) {
                inimigo.classificadoComoBasico = true;
                inimigo.classificadoComoIntermediario = false;
                inimigo.classificadoComoSurfer = false;
            } else if (isFollower) {
                inimigo.classificadoComoBasico = false;
                inimigo.classificadoComoIntermediario = true;
                inimigo.classificadoComoSurfer = false;
            } else {
                inimigo.classificadoComoBasico = false;
                inimigo.classificadoComoIntermediario = false;
            }
            
            double velLateral = inimigo.velocidade * Math.sin(inimigo.direcao - (getHeadingRadians() + inimigo.anguloAbsolutoRadianos));
            if (Math.abs(velLateral) > 0.1 && Math.abs(inimigo.ultimaVelocidadeLateral) > 0.1) {
                if (Utilitario.sinal(velLateral) != Utilitario.sinal(inimigo.ultimaVelocidadeLateral)) {
                    inimigo.reversoesLaterais++; 
                    inimigo.ticksDesdeReversao = 0;
                } else {
                    inimigo.ticksDesdeReversao++;
                }
            } else {
                inimigo.ticksDesdeReversao++;
            }
            inimigo.ultimaVelocidadeLateral = velLateral;
            
            double perpendicularidade = Math.abs(Math.cos(inimigo.direcao - (getHeadingRadians() + inimigo.anguloAbsolutoRadianos)));
            if (perpendicularidade < 0.6 && inimigo.saldoPrecisao < -1.0 && inimigo.reversoesLaterais > 2) {
                inimigo.fatorSurf = Math.min(1.0, inimigo.fatorSurf + 0.1);
            } else {
                inimigo.fatorSurf = Math.max(0.0, inimigo.fatorSurf - 0.02);
            }
            
            if (inimigo.fatorSurf > 0.4) {
                inimigo.classificadoComoSurfer = true;
            } else if (inimigo.fatorSurf == 0.0 && inimigo.reversoesLaterais < 3) {
                inimigo.classificadoComoSurfer = false;
            }
            
            inimigo.pontuacaoDisparo = inimigo.energia < 25 ? (inimigo.energia < 5 ?
                    (inimigo.energia == 0 ? Double.MIN_VALUE : inimigo.distance(meuRobo) * 0.1) :
                    inimigo.distance(meuRobo) * 0.75) : inimigo.distance(meuRobo);
                    
            inimigo.pontuacaoDisparo -= (inimigo.saldoPrecisao * 25.85); 
            
            boolean isAmeacaAvancada = (inimigo.agressividade > 0.5 || inimigo.saldoPrecisao > 10.0 || inimigo.classificadoComoSurfer);
            double raioCaca = (getOthers() > 1) ? 800 : 450; 
            if (isAmeacaAvancada && inimigo.distance(meuRobo) <= raioCaca) {
                inimigo.pontuacaoDisparo -= 100000.0; 
            }
                    
            if (getOthers() == 1) {
                setTurnRadarLeftRadians(getRadarTurnRemainingRadians()); 
            }
            
            if (!alvo.vivo || inimigo.pontuacaoDisparo < alvo.pontuacaoDisparo) {
                alvo = inimigo;
            }
            
            // SALVAR ESTADO ADVERSÁRIO PARA PRÓXIMO ROUND
            if (inimigo != null) {
                if (this.ultimaVGEscolhida != -1) {
                    historicoArmaPorInimigo.put(nomeInimigo, this.ultimaVGEscolhida);
                }
                historicoSurferPorInimigo.put(nomeInimigo, inimigo.classificadoComoSurfer);
                historicoBasicoPorInimigo.put(nomeInimigo, inimigo.classificadoComoBasico);
                historicoIntermediarioPorInimigo.put(nomeInimigo, inimigo.classificadoComoIntermediario);
            }

        }
        else { 
            setScanColor(Color.red); 
            String nomeInimigo = e.getName();
            Robo inimigo = listaInimigos.get(nomeInimigo);
            
            if (inimigo == null) {
                inimigo = new Robo();
                inimigo.nome = nomeInimigo;
                listaInimigos.put(nomeInimigo, inimigo);
                
                // RESTAURAR ESTADO SALVO DO ROUND ANTERIOR NO INÍCIO DESTE ROUND
                if (historicoArmaPorInimigo.containsKey(nomeInimigo)) {
                    this.ultimaVGEscolhida = historicoArmaPorInimigo.get(nomeInimigo);
                }
                if (historicoSurferPorInimigo.containsKey(nomeInimigo)) {
                    inimigo.classificadoComoSurfer = historicoSurferPorInimigo.get(nomeInimigo);
                }
                if (historicoBasicoPorInimigo.containsKey(nomeInimigo)) {
                    inimigo.classificadoComoBasico = historicoBasicoPorInimigo.get(nomeInimigo);
                }
                if (historicoIntermediarioPorInimigo.containsKey(nomeInimigo)) {
                    inimigo.classificadoComoIntermediario = historicoIntermediarioPorInimigo.get(nomeInimigo);
                }
            }
            
            if (alvo != null && alvo.nome != null && alvo.nome.equals(nomeInimigo) && getTime() < 5) {
                if (historicoArmaPorInimigo.containsKey(nomeInimigo)) {
                    this.ultimaVGEscolhida = historicoArmaPorInimigo.get(nomeInimigo);
                }
            }

            alvo = inimigo; 

            inimigo.anguloAbsolutoRadianos = getHeadingRadians() + e.getBearingRadians();
            inimigo.distancia = e.getDistance();
            inimigo.velocidade = e.getVelocity();
            inimigo.direcao = e.getHeadingRadians(); 
            inimigo.ultimaDirecao = velocidadeInimigoAnterior == 0 ? inimigo.direcao : (e.getHeadingRadians() - (e.getVelocity() - velocidadeInimigoAnterior)); 
            
            if (Math.abs(e.getVelocity()) < 1.5) {
                inimigoTicksParado_1v1++;
            } else {
                inimigoTicksParado_1v1 = 0;
            }
            
            inimigo.setLocation(Utilitario.projetar(new Point2D.Double(getX(), getY()), inimigo.anguloAbsolutoRadianos, inimigo.distancia));
            
            inimigo.historicoVelocidade.addFirst(inimigo.velocidade);
            inimigo.historicoDeltaDirecao.addFirst(inimigo.direcao - inimigo.ultimaDirecao);
            if (inimigo.historicoVelocidade.size() > 50) inimigo.historicoVelocidade.removeLast();
            if (inimigo.historicoDeltaDirecao.size() > 50) inimigo.historicoDeltaDirecao.removeLast();
            
            double distParedeInimigo = Math.min(
                Math.min(inimigo.x, campoBatalha.width - inimigo.x), 
                Math.min(inimigo.y, campoBatalha.height - inimigo.y)
            );
            boolean isClinger = distParedeInimigo < 60; 
            boolean isLinear = inimigo.reversoesLaterais < 2 && inimigo.historicoVelocidade.size() >= 30;
            
            // NOVO: Detecção de seguidor/perseguidor (Intermediário) no 1v1
            boolean isFollower = false;
            if (inimigo.historicoVelocidade.size() > 10) {
                double anguloParaBT = Utils.normalAbsoluteAngle(getHeadingRadians() + e.getBearingRadians() + Math.PI);
                double moveDir = (inimigo.velocidade >= 0) ? inimigo.direcao : Utils.normalAbsoluteAngle(inimigo.direcao + Math.PI);
                double difAngulo = Math.abs(Utils.normalRelativeAngle(moveDir - anguloParaBT));
                
                if (difAngulo < 0.35 && Math.abs(inimigo.velocidade) > 1.0) {
                     isFollower = true;
                }
            }

            if (isClinger || isLinear) {
                inimigo.classificadoComoBasico = true;
                inimigo.classificadoComoIntermediario = false;
                inimigo.classificadoComoSurfer = false;
            } else if (isFollower) {
                inimigo.classificadoComoBasico = false;
                inimigo.classificadoComoIntermediario = true;
                inimigo.classificadoComoSurfer = false;
            } else {
                inimigo.classificadoComoBasico = false;
                inimigo.classificadoComoIntermediario = false;
            }
            
            double velLateralAtual = inimigo.velocidade * Math.sin(inimigo.direcao - (getHeadingRadians() + inimigo.anguloAbsolutoRadianos));
            
            if (Math.abs(velLateralAtual) > 0.1 && Math.abs(inimigo.ultimaVelocidadeLateral) > 0.1) {
                if (Utilitario.sinal(velLateralAtual) != Utilitario.sinal(inimigo.ultimaVelocidadeLateral)) {
                    inimigo.reversoesLaterais++;
                    inimigo.ticksDesdeReversao = 0;
                } else {
                    inimigo.ticksDesdeReversao++;
                }
            } else {
                inimigo.ticksDesdeReversao++;
            }
            inimigo.ultimaVelocidadeLateral = velLateralAtual;
            
            double perpendicularidade = Math.abs(Math.cos(inimigo.direcao - (getHeadingRadians() + inimigo.anguloAbsolutoRadianos)));
            if (perpendicularidade < 0.6 && inimigo.saldoPrecisao < -1.0 && inimigo.reversoesLaterais > 2) {
                inimigo.fatorSurf = Math.min(1.0, inimigo.fatorSurf + 0.1);
            } else {
                inimigo.fatorSurf = Math.max(0.0, inimigo.fatorSurf - 0.02);
            }
            
            if (perpendicularidade < 0.8 && inimigo.reversoesLaterais > 4) {
                inimigo.fatorSurf = Math.min(1.0, inimigo.fatorSurf + 0.15);
            }
            if (inimigo.fatorSurf > 0.25 || (inimigo.reversoesLaterais > 6 && !isClinger)) {
                inimigo.classificadoComoSurfer = true;
                inimigo.classificadoComoBasico = false;
            } else if (inimigo.fatorSurf <= 0.1 && inimigo.reversoesLaterais < 3) {
                inimigo.classificadoComoSurfer = false;
            }
            
            if (inimigo.velocidade != 0) {
                direcaoLateral = Utilitario.sinal(velLateralAtual);
            }
                
            Onda onda = new Onda(this);
            onda.posicaoCanhao = new Point2D.Double(getX(), getY());
            onda.posicaoAlvo = inimigo;
            onda.direcaoLateralOnda = direcaoLateral;
            onda.definirSegmentacoes(inimigo.distancia, inimigo.velocidade, velocidadeInimigoAnterior);
            
            double aceleracaoAtual = inimigo.velocidade - velocidadeInimigoAnterior;
            onda.featuresKNN = new double[] {
                inimigo.velocidade,
                inimigo.direcao - inimigo.ultimaDirecao,
                inimigo.distancia,
                velLateralAtual,
                aceleracaoAtual,
                inimigo.ticksDesdeReversao
            };
            
            if(alvo != null && alvo.saldoPrecisao > 5) {
                onda.pesoImpacto = 12.0; 
            }
            
            velocidadeInimigoAnterior = inimigo.velocidade;
            onda.angulo = inimigo.anguloAbsolutoRadianos;
            
            potenciaTiroCorrente = Math.min(3, Math.min(this.getEnergy(), e.getEnergy()) / 4.0);
            if (getEnergy() < 2 && e.getDistance() < 500) {
                potenciaTiroCorrente = 0.1; 
            } else if (e.getDistance() >= 500) {
                potenciaTiroCorrente = 1.1; 
            }
            if (inimigo.distancia < 150) {
                potenciaTiroCorrente = Math.min(3.0, getEnergy() / 12); 
            }
            if (alvo != null && alvo.saldoPrecisao > 40.0) {
                potenciaTiroCorrente = Math.max(potenciaTiroCorrente, 2.9); 
            }
            onda.potenciaTiro = potenciaTiroCorrente;
            
            if (inimigoTicksParado_1v1 > 5) {
                setTurnGunRightRadians(Utils.normalRelativeAngle(inimigo.anguloAbsolutoRadianos - getGunHeadingRadians()));
                
                miraVisual.setLocation(
                    getX() + Math.sin(inimigo.anguloAbsolutoRadianos) * inimigo.distancia,
                    getY() + Math.cos(inimigo.anguloAbsolutoRadianos) * inimigo.distancia
                );
                
                boolean atirouReal = false;
                if (getGunHeat() == 0.0 && Math.abs(getGunTurnRemaining()) < 0.05) { 
                    Bullet b = setFireBullet(onda.potenciaTiro);
                    if (b != null) {
                        atirouReal = true;
                        totalTirosDisparados++;
                    }
                }
                
                if ((atirouReal || getTime() >= tempoArmaVirtual_1v1) && getEnergy() >= onda.potenciaTiro) {
                    onda.alvoOnda = inimigo; 
                    addCustomEvent(onda);
                    tempoArmaVirtual_1v1 = getTime() + (long) Math.ceil(Rules.getGunHeat(onda.potenciaTiro) / getGunCoolingRate());
                }
                
            } else {
                double tempoEstimadoVoo = inimigo.distancia / Rules.getBulletSpeed(onda.potenciaTiro);
                onda.registrarMirasAuxiliares(inimigo, meuRobo, tempoEstimadoVoo);
                onda.registrarMirasARMA_ARIMA(inimigo, meuRobo, tempoEstimadoVoo, getOthers());
                
                onda.registrarMiraKNNPesado(inimigo, meuRobo);
                onda.registrarMiraPredict1v1(inimigo, meuRobo, Rules.getBulletSpeed(onda.potenciaTiro)); 
                
                double offsetFinal = onda.offsetAnguloMaisVisitado();
                onda.offsetEscolhido = offsetFinal; 
                
                miraVisual.setLocation(
                    getX() + Math.sin(onda.angulo + offsetFinal) * inimigo.distancia,
                    getY() + Math.cos(onda.angulo + offsetFinal) * inimigo.distancia
                );
                
                setTurnGunRightRadians(Utils.normalRelativeAngle(
                        inimigo.anguloAbsolutoRadianos - getGunHeadingRadians() + offsetFinal));
                
                if (ultimaVGEscolhida != vgAnteriorLog) {
                    if (ultimaVGEscolhida == -1) {
                        System.out.println("[BT-7274] Trocando Arma -> Coletando Dados (Padrão)");
                    } else {
                        String txtAdicional = "";
                        double precisaoGlobalLog = totalTirosDisparados > 0 ? ((double)totalTirosAcertados / totalTirosDisparados) * 100.0 : 100.0;
                        boolean forcarPorDerrotasLog = (alvo != null && alvo.nome != null && derrotasSeguidas.getOrDefault(alvo.nome, 0) > 5);
                        boolean ehAvancadoLog = (alvo != null && (!alvo.classificadoComoBasico && !alvo.classificadoComoIntermediario || alvo.classificadoComoSurfer || alvo.reversoesLaterais > 3));
                        
                        if (getOthers() <= 1 && ehAvancadoLog && !escolheuArmaNaturalmente && ultimaVGEscolhida == 8) {
                            txtAdicional = " (Forçada - Surfing 1v1 Dedicado Ativo!)";
                        } else if (forcarPorDerrotasLog && !escolheuArmaNaturalmente) {
                            txtAdicional = " (Forçada - >5 Derrotas Seguidas! Alternando)";
                        } else if (!escolheuArmaNaturalmente && ultimaVGEscolhida >= 7 && ultimaVGEscolhida != 0 && ultimaVGEscolhida != 8) {
                            txtAdicional = " (Forçada por Inatividade / Alternando)";
                        } else if (totalTirosDisparados >= 15 && precisaoGlobalLog < 10.0 && !escolheuArmaNaturalmente) {
                            txtAdicional = " (Forçada - Precisão Global < 10%! Alternando)"; 
                        } else if (getOthers() <= 1 && alvo != null && !alvo.classificadoComoSurfer && ultimaVGEscolhida == 0) {
                            txtAdicional = " (Lock: Auxiliar Anti-NonSurfer Ativo!)";
                        } else if (alvo != null && alvo.classificadoComoSurfer && ultimaVGEscolhida == 8) {
                            txtAdicional = " (Lock: GunWave GF vs Surfer!)";
                        }
                        System.out.println("[BT-7274] Trocando Arma -> " + NOMES_VG[ultimaVGEscolhida] + txtAdicional);
                    }
                    vgAnteriorLog = ultimaVGEscolhida;
                }

                boolean atirouReal = false;
                if (getGunHeat() == 0.0 && Math.abs(getGunTurnRemaining()) < 0.05) { 
                    Bullet b = setFireBullet(onda.potenciaTiro);
                    if (b != null) {
                        atirouReal = true;
                        totalTirosDisparados++;
                        if (ultimaVGEscolhida != -1) {
                            rastreioBalasVG.put(b, ultimaVGEscolhida); 
                            disparosReaisVG[ultimaVGEscolhida]++;
                        }
                    }
                }
                
                if ((atirouReal || getTime() >= tempoArmaVirtual_1v1) && getEnergy() >= onda.potenciaTiro) {
                    onda.alvoOnda = inimigo; 
                    addCustomEvent(onda); 
                    ondasAtivas.add(onda); 
                    
                    tempoArmaVirtual_1v1 = getTime() + (long) Math.ceil(Rules.getGunHeat(onda.potenciaTiro) / getGunCoolingRate());
                }
            }
            
            double quedaEnergia = energiaInimigoAnterior_1v1 - e.getEnergy();
            if (quedaEnergia > 0 && quedaEnergia <= 3.0) {
                inimigo.agressividade += 0.1; 
                
                OndaInimiga ondaInimiga = new OndaInimiga();
                ondaInimiga.tempoDisparo = getTime() - 1;
                ondaInimiga.velocidadeBala = Rules.getBulletSpeed(quedaEnergia);
                ondaInimiga.origem = (Point2D.Double) Utilitario.projetar(new Point2D.Double(getX(), getY()), inimigo.anguloAbsolutoRadianos, inimigo.distancia);
                ondaInimiga.anguloDireto = Utilitario.anguloAbsoluto(ondaInimiga.origem, new Point2D.Double(getX(), getY()));
                ondaInimiga.direcaoLateral = Utilitario.sinal(getVelocity() * Math.sin(getHeadingRadians() - ondaInimiga.anguloDireto));
                if (ondaInimiga.direcaoLateral == 0) ondaInimiga.direcaoLateral = 1;
                ondasInimigas.add(ondaInimiga);
            }
            energiaInimigoAnterior_1v1 = e.getEnergy();
                
            movimento1VS1.onScannedRobot(e); 
            
            if (inimigo.distancia < 250) { 
                setTurnRightRadians(Utils.normalRelativeAngle(inimigo.anguloAbsolutoRadianos + Math.PI/2 - 0.5));
                setAhead(100);
            } else {
                if (habilitarSurfing) {
                    boolean deveSurfar = true;
                    if (alvo != null && alvo.classificadoComoBasico && !alvo.classificadoComoSurfer) {
                        deveSurfar = false;
                    }
                    if (deveSurfar) {
                        executarSurfing(); 
                    }
                }
            }

            double anguloRadar = Utils.normalRelativeAngle(inimigo.anguloAbsolutoRadianos - getRadarHeadingRadians());
            setTurnRadarRightRadians(anguloRadar * 2.0);
            
            // SALVAR ESTADO ADVERSÁRIO PARA PRÓXIMO ROUND
            if (inimigo != null) {
                if (this.ultimaVGEscolhida != -1) {
                    historicoArmaPorInimigo.put(nomeInimigo, this.ultimaVGEscolhida);
                }
                historicoSurferPorInimigo.put(nomeInimigo, inimigo.classificadoComoSurfer);
                historicoBasicoPorInimigo.put(nomeInimigo, inimigo.classificadoComoBasico);
                historicoIntermediarioPorInimigo.put(nomeInimigo, inimigo.classificadoComoIntermediario);
            }
        }
    }

    // =========================================================
    // EVENTOS DE TELEMETRIA E SOBREVIVÊNCIA DE SISTEMA
    // =========================================================
    public void onSkippedTurn(SkippedTurnEvent e) {
        turnosPulados++; 
    }

    private void exibirMetricas() {
        if (!escolheuArmaNaturalmente) {
            roundsSemEscolha++;
        } else {
            roundsSemEscolha = 0; 
        }
        
        double hitRate = totalTirosDisparados > 0 ? ((double)totalTirosAcertados / totalTirosDisparados) * 100.0 : 0.0;
        System.out.println("=================================================");
        System.out.println(" RELATÓRIO TÁTICO BT-7274 (FIM DE ROUND)");
        System.out.println("=================================================");
        
        if (ultimaVGEscolhida == -1) {
            System.out.println(" Arma Ativa Final : Coletando Dados (Padrão)");
        } else {
            String sufixo = "";
            boolean forcarPorDerrotasMetrica = (alvo != null && alvo.nome != null && derrotasSeguidas.getOrDefault(alvo.nome, 0) > 5);
            boolean ehAvancadoMetrica = (alvo != null && (!alvo.classificadoComoBasico && !alvo.classificadoComoIntermediario || alvo.classificadoComoSurfer || alvo.reversoesLaterais > 3));
            
            if (getOthers() <= 1 && ehAvancadoMetrica && !escolheuArmaNaturalmente && ultimaVGEscolhida == 8) sufixo = " (Lock: Surfing 1v1 Dedicado)";
            else if (forcarPorDerrotasMetrica && !escolheuArmaNaturalmente) sufixo = " (Lock Vingança: Alternando)";
            else if (!escolheuArmaNaturalmente && ultimaVGEscolhida >= 7 && ultimaVGEscolhida != 0 && ultimaVGEscolhida != 8) sufixo = " (Forçada por Inatividade / Alternando)";
            else if (totalTirosDisparados >= 15 && hitRate < 10.0 && !escolheuArmaNaturalmente) sufixo = " (Lock Precaução: Precisão < 10% - Alternando)";
            else if (getOthers() <= 1 && alvo != null && !alvo.classificadoComoSurfer && ultimaVGEscolhida == 0) sufixo = " (Lock: Auxiliar Anti-NonSurfer)";
            else if (alvo != null && alvo.classificadoComoSurfer && ultimaVGEscolhida == 8) sufixo = " (Lock: GunWave GF vs Surfer)";
            
            System.out.println(" Arma Ativa Final : " + NOMES_VG[ultimaVGEscolhida] + sufixo);
        }
        
        System.out.println(" Tiros Disparados : " + totalTirosDisparados);
        System.out.println(" Tiros Acertados  : " + totalTirosAcertados);
        System.out.println(" HIT RATE GLOBAL  : " + String.format(Locale.US, "%.2f", hitRate) + "%");
        System.out.println(" Turnos Pulados   : " + turnosPulados + " (Skipped Turns)");
        System.out.println(" Rounds Sem Arma  : " + roundsSemEscolha);
        if (alvo != null && alvo.nome != null) {
            System.out.println(" Derrotas p/ Alvo : " + derrotasSeguidas.getOrDefault(alvo.nome, 0));
        }
        
        System.out.println("-------------------------------------------------");
        System.out.println(" PRECISÃO TEÓRICA DAS VTs (Simulação interna):");
        if (alvo != null) {
            for (int i = 0; i < 10; i++) {
                System.out.println(String.format(Locale.US, " [%-15s] Pontuação Virtual: %.4f", NOMES_VG[i], alvo.acertosVirtualGuns[i]));
            }
        } else {
            System.out.println(" Nenhum alvo rastreado para simulação.");
        }

        System.out.println("-------------------------------------------------");
        System.out.println(" PERFORMANCE DAS VIRTUAL GUNS USADAS (1v1):");
        for (int i = 0; i < 10; i++) {
            if (disparosReaisVG[i] > 0) {
                double vgRate = ((double)acertosReaisVG[i] / disparosReaisVG[i]) * 100.0;
                System.out.println(String.format(Locale.US, " [%-15s] Usada: %3d | Acertos: %3d | Precisão: %5.2f%%", 
                    NOMES_VG[i], disparosReaisVG[i], acertosReaisVG[i], vgRate));
            }
        }
        
        System.out.println("-------------------------------------------------");
        System.out.println(" PERFIL DOS INIMIGOS RASTREADOS:");
        for (Robo r : listaInimigos.values()) {
            String tipo = "Avançado";
            if (r.classificadoComoSurfer) tipo = "SURFER (Avançado)";
            else if (r.classificadoComoIntermediario) tipo = "Intermediário (Perseguidor)";
            else if (r.classificadoComoBasico) tipo = "Básico (Padrão)";
            
            System.out.println(String.format(Locale.US, " [%-15s] Tipo: %-17s | Rev. Lat: %3d | Agr: %5.2f | Prec: %5.2f", 
                r.nome != null ? r.nome : "Desconhecido", tipo, r.reversoesLaterais, r.agressividade, r.saldoPrecisao));
        }

        System.out.println("-------------------------------------------------");
        System.out.println(" TELEMETRIA DE MOVIMENTAÇÃO (FINAL):");
        
        double pesoS_Final = confiancaSurfing;
        double pesoM_Final = confiancaMRM;
        if (getOthers() <= 1 && alvo != null && alvo.classificadoComoSurfer) {
            pesoS_Final = 1.0;
            pesoM_Final = 0.0;
        } else if (getOthers() <= 1 && alvo != null && !alvo.classificadoComoSurfer) {
            pesoS_Final = 0.0; 
            pesoM_Final *= 3.0; 
        } else if (getOthers() > 1) { 
            pesoS_Final *= 2.5;
        }
        
        System.out.println(String.format(Locale.US, " Confiança Final  -> Surfing: %.2f | MRM: %.2f", pesoS_Final, pesoM_Final));
        if (getOthers() <= 1 && alvo != null && alvo.classificadoComoSurfer) {
             System.out.println(" Override 1v1     : MRM Desativado (Foco TOTAL no Wave Surfing)");
        } else if (getOthers() <= 1 && alvo != null && !alvo.classificadoComoSurfer) {
             System.out.println(" Override 1v1     : Wave Surfing DESATIVADO - Modo Predador MRM Ativo");
        } else if (getOthers() > 1) {
             System.out.println(" Override Melee   : Peso do Surfing Aumentado (+150% Sobrevivência)");
        }
        
        boolean ehAvancadoConsole = (alvo != null && (!alvo.classificadoComoBasico && !alvo.classificadoComoIntermediario || alvo.classificadoComoSurfer || alvo.reversoesLaterais > 3));
        if (alvo != null && !alvo.classificadoComoSurfer) { ehAvancadoConsole = false; }
        
        System.out.println(" Buffer Surfing   : " + ((getOthers() <= 1 && ehAvancadoConsole) ? "1v1 Dedicado (c/ Inertia)" : "Melee Geral"));
        System.out.println(" Modo de Fuga     : " + (modoFuga ? "ATIVO no fim do round" : "Inativo"));
        System.out.println("=================================================");
    }

    public void onHitByBullet(HitByBulletEvent e) {
        Robo inimigo = listaInimigos.get(e.getName());
        if (inimigo != null) {
            inimigo.agressividade += 0.5; 
            inimigo.fatorAmeaca *= 1.1; 
        }
        
        if (confiancaSurfing >= confiancaMRM) {
            confiancaSurfing = Math.max(0.1, confiancaSurfing - 0.3);
            confiancaMRM = Math.min(3.0, confiancaMRM + 0.15);
        } else {
            confiancaMRM = Math.max(0.1, confiancaMRM - 0.3);
            confiancaSurfing = Math.min(3.0, confiancaSurfing + 0.15);
        }
    }
    
    public void onBulletHit(BulletHitEvent e) {
        totalTirosAcertados++; 
        Robo inimigo = listaInimigos.get(e.getName());
        if (inimigo != null) {
            inimigo.saldoPrecisao += 15.0; 
        }
        
        if (rastreioBalasVG.containsKey(e.getBullet())) {
            acertosReaisVG[rastreioBalasVG.get(e.getBullet())]++;
        }
    }

    public void onBulletMissed(BulletMissedEvent e) {
        if (alvo != null && alvo.vivo) {
            alvo.saldoPrecisao -= 5.0; 
        }
    }
    
    public void onBulletHitBullet(BulletHitBulletEvent e) {
        if (alvo != null && alvo.vivo) {
            alvo.saldoPrecisao -= 1.0; 
        }
    }

    public void onRobotDeath(RobotDeathEvent event) {
        if (listaInimigos.containsKey(event.getName())) {
            listaInimigos.get(event.getName()).vivo = false;
        }
        if (alvo != null && alvo.nome != null && event.getName().equals(alvo.nome)) {
            alvo.vivo = false;
        }
    }
    
    public void onDeath(DeathEvent event) {
        if (alvo != null && alvo.nome != null) {
            derrotasSeguidas.put(alvo.nome, derrotasSeguidas.getOrDefault(alvo.nome, 0) + 1); 
        }
        exibirMetricas();
    }
    
    public void onWin(WinEvent event) {
        if (alvo != null && alvo.nome != null) {
            derrotasSeguidas.put(alvo.nome, 0); 
        }
        exibirMetricas();
        
        System.out.println("=================================================");
        System.out.println("[BT-7274] Protocolo 3: Proteger o Piloto.");
        System.out.println("[BT-7274] Confie em mim.");
        System.out.println("=================================================");
        
        while (true) {
            coresBT7274(); 
            turnRadarRight(360);
        }
    }

    // =========================================================
    // PROTOCOLO DE SOBREVIVÊNCIA E FUGA
    // =========================================================
    public void verificarFuga() {
        if (meuRobo.energia < 25 || (getOthers() >= 4 && meuRobo.energia < 45)) {
            modoFuga = true; 
        } else {
            modoFuga = false;
        }
    }

    // =========================================================
    // LÓGICA DE DISPARO (MELEE PREDITIVO COMUM)
    // =========================================================
    public void disparar() {
        if (alvo != null && alvo.vivo) {
            double distancia = meuRobo.distance(alvo);
            double potencia = (distancia > 850 ? 0.1 : (distancia > 700 ? 0.5 : (distancia > 250 ? 2.0 : 3.0)));
            
            potencia = Math.min(meuRobo.energia / 4d, Math.min(alvo.energia / 3d, potencia));
            potencia = Utilitario.limitar(potencia, 0.1, 3.0);
            
            if (distancia < 150) {
                potencia = Math.min(3.0, meuRobo.energia / 12); 
            }
            
            long tempoAteAcerto;
            Point2D.Double mirarEm = new Point2D.Double();
            double direcao, deltaDirecao, velocidadeTiro;
            double preverX, preverY;
            
            preverX = alvo.getX();
            preverY = alvo.getY();
            direcao = alvo.direcao;
            deltaDirecao = direcao - alvo.ultimaDirecao;
            
            mirarEm.setLocation(preverX, preverY);
            tempoAteAcerto = 0;
            
            if (alvo.ticksParado <= 5) {
                do {
                    preverX += Math.sin(direcao) * alvo.velocidade;
                    preverY += Math.cos(direcao) * alvo.velocidade;
                    direcao += deltaDirecao;
                    tempoAteAcerto++;
                    
                    Rectangle2D.Double areaDisparo = new Rectangle2D.Double(
                        MARGEM_PAREDE, MARGEM_PAREDE,
                        campoBatalha.width - MARGEM_PAREDE, campoBatalha.height - MARGEM_PAREDE
                    );
                    
                    if (!areaDisparo.contains(preverX, preverY)) {
                        velocidadeTiro = mirarEm.distance(meuRobo) / tempoAteAcerto;
                        potencia = Utilitario.limitar((20 - velocidadeTiro) / 3.0, 0.1, 3.0);
                        break;
                    }
                    mirarEm.setLocation(preverX, preverY);
                    
                } while ((int) Math.round((mirarEm.distance(meuRobo) - MARGEM_PAREDE) / Rules.getBulletSpeed(potencia)) > tempoAteAcerto);
            }
            
            mirarEm.setLocation(
                Utilitario.limitar(preverX, 34, getBattleFieldWidth() - 34),
                Utilitario.limitar(preverY, 34, getBattleFieldHeight() - 34)
            );
            
            if ((getGunHeat() == 0.0) && (getGunTurnRemaining() == 0.0) && (potencia > 0.0) && (meuRobo.energia > 0.1)) {
                Bullet b = setFireBullet(potencia);
                if (b != null) totalTirosDisparados++;
            }
            
            setTurnGunRightRadians(Utils.normalRelativeAngle(
                ((Math.PI / 2) - Math.atan2(mirarEm.y - meuRobo.getY(), mirarEm.x - meuRobo.getX())) - getGunHeadingRadians()
            ));
        }
    }

    // =========================================================
    // LÓGICA DE MOVIMENTO MINIMUM RISK (MRM)
    // =========================================================
    public void movimento() {
        if (pontoAlvo.distance(meuRobo) < 15 || tempoInativo > 25) {
            tempoInativo = 0;
            
            int pontosAmostragem = (getOthers() > 1) ? 144 : 36;
            
            if (getOthers() <= 1 && alvo != null && alvo.classificadoComoBasico) {
                pontosAmostragem = 12; 
            }
            
            atualizarListaPosicoes(pontosAmostragem); 
            
            Point2D.Double pontoMenorRisco = null;
            double menorRisco = Double.MAX_VALUE;
            double melhorRiscoSurf = 0;
            double melhorRiscoMRM = 0;
            
            for (int i = 0; i < qtdPontosAtivos; i++) {
                Point2D.Double p = posicoesPossiveis.get(i);
                double riscoAtual = avaliarPonto(p);
                if (riscoAtual <= menorRisco || pontoMenorRisco == null) {
                    menorRisco = riscoAtual;
                    pontoMenorRisco = p;
                    melhorRiscoSurf = ultimoRiscoSurfingAvaliado;
                    melhorRiscoMRM = ultimoRiscoMRMAvaliado;
                }
            }
            pontoAlvo = pontoMenorRisco;
            riscoSurfingAlvoAtual = melhorRiscoSurf;
            riscoMRMAlvoAtual = melhorRiscoMRM;
            
        } else {
            tempoInativo++;
            double angulo = Utilitario.anguloAbsoluto(meuRobo, pontoAlvo) - getHeadingRadians();
            double direcao = 1;
            
            if (Math.cos(angulo) < 0) {
                angulo += Math.PI;
                direcao *= -1; 
            }
            
            double maxVel = 10 - (4 * Math.abs(getTurnRemainingRadians())); 
            
            if (getOthers() <= 1 && !ondasInimigas.isEmpty()) {
                double roletaJitter = Math.random();
                if (roletaJitter < 0.08) {
                    maxVel = 0.0; 
                } else if (roletaJitter < 0.15) {
                    maxVel = Math.random() * 6.0; 
                }
            }
            
            caminhoSurfingVisualizado.clear();
            double visX = meuRobo.x;
            double visY = meuRobo.y;
            for (int i = 1; i <= 15; i++) {
                visX += Math.sin(getHeadingRadians() + angulo) * (maxVel * direcao);
                visY += Math.cos(getHeadingRadians() + angulo) * (maxVel * direcao);
                caminhoSurfingVisualizado.add(new Point2D.Double(visX, visY));
            }
            
            setMaxVelocity(maxVel);
            setAhead(meuRobo.distance(pontoAlvo) * direcao); 
            
            angulo = Utils.normalRelativeAngle(angulo);
            setTurnRightRadians(angulo);
        }
    }

    public void atualizarListaPosicoes(int n) {
        int index = 0;
        double maxDist = 200.0;
        int rings = (n > 36) ? 4 : 1; 
        int anglesPerRing = n / rings;
        
        for (int r = 1; r <= rings; r++) {
            double dist = (maxDist / rings) * r;
            for (int a = 0; a < anglesPerRing; a++) {
                if (index >= posicoesPossiveis.size()) break; 
                
                double angle = (Math.PI * 2.0 * a) / anglesPerRing;
                double x = meuRobo.x + Math.sin(angle) * dist;
                double y = meuRobo.y + Math.cos(angle) * dist;
                
                x = Utilitario.limitar(x, 75, campoBatalha.width - 75);
                y = Utilitario.limitar(y, 75, campoBatalha.height - 75);
                
                posicoesPossiveis.get(index).setLocation(x, y);
                index++;
            }
        }
        
        if (index < posicoesPossiveis.size()) {
            posicoesPossiveis.get(index).setLocation(meuRobo.x, meuRobo.y); 
            index++;
        }
        qtdPontosAtivos = index;
    }
    
    public double avaliarPonto(Point2D.Double p) {
        double riscoTotal = 0;
        double riscoSurfing = 0; 
        double riscoMRM = 0;     
        
        double px = p.x;
        double py = p.y;
        double cw = campoBatalha.width;
        double ch = campoBatalha.height;

        double distRoboPSq = (px - meuRobo.x) * (px - meuRobo.x) + (py - meuRobo.y) * (py - meuRobo.y);
        double distRoboP = Math.sqrt(distRoboPSq);

        int numOthers = getOthers();
        
        boolean modoShadowLight = (numOthers <= 1 && alvo != null && alvo.classificadoComoBasico);

        if (alvo != null && alvo.vivo && alvo.classificadoComoBasico) {
            double distParaAlvo = Math.sqrt((alvo.x - px)*(alvo.x - px) + (alvo.y - py)*(alvo.y - py));
            double desvioOrbita = Math.abs(distParaAlvo - 450.0); 
            riscoMRM += (desvioOrbita * 35.0); 
        }

        if (habilitarSurfing && !ondasInimigas.isEmpty()) {
            double votoSurfing = 0;
            int ondasProcessadas = 0;
            
            for (OndaInimiga onda : ondasInimigas) {
                if (modoShadowLight && ondasProcessadas > 0) break;
                ondasProcessadas++;
                
                int binDoPonto = onda.obterBin(px, py);
                double riscoOnda = 0;
                
                int rangeAvaliacao = modoShadowLight ? 0 : 2;
                
                boolean isAvancado = (alvo != null && (!alvo.classificadoComoBasico && !alvo.classificadoComoIntermediario || alvo.classificadoComoSurfer || alvo.reversoesLaterais > 3));
                int[] bufferSurf = (numOthers <= 1 && isAvancado) ? estatisticasSurfing1v1 : estatisticasSurfing;
                
                for (int i = -rangeAvaliacao; i <= rangeAvaliacao; i++) {
                    int binAvaliado = (int) Utilitario.limitar(binDoPonto + i, 0, OndaInimiga.BINS_SURF - 1);
                    riscoOnda += bufferSurf[binAvaliado] * (1.0 / (Math.abs(i) + 1));
                }
                
                double distVoo = (getTime() - onda.tempoDisparo) * onda.velocidadeBala;
                
                double dxOnda = px - onda.origem.x;
                double dyOnda = py - onda.origem.y;
                double distRestante = Math.sqrt(dxOnda * dxOnda + dyOnda * dyOnda) - distVoo;
                
                if (distRestante > 0) {
                    votoSurfing += (riscoOnda * 8500.0) / Math.max(1, distRestante);
                }
            }
            riscoSurfing += votoSurfing;
            
            if (numOthers > 4) {
                riscoSurfing *= 5.0; 
            }
        }
        
        double votoEstabilidade = Utilitario.aleatorioEntre(1.15, 2.42) / Math.max(1, distRoboPSq);
        riscoMRM += votoEstabilidade;
        
        double fatorMultidao = (6.85 * Math.max(0, numOthers - 1));
        
        double cx = cw / 2.0;
        double cy = ch / 2.0;
        double distCenterSq = (px - cx)*(px - cx) + (py - cy)*(py - cy);
        double votoCentro = fatorMultidao / Math.max(1, distCenterSq);
        
        double pesoCanto = numOthers <= 5 ? (numOthers == 1 ? 0.32 : 0.58) : 1.15;
        if (modoFuga) {
            pesoCanto *= 0.12;
            votoCentro = 0; 
        }
        
        double distC1 = px*px + py*py;
        double distC2 = (cw-px)*(cw-px) + py*py;
        double distC3 = px*px + (ch-py)*(ch-py);
        double distC4 = (cw-px)*(cw-px) + (ch-py)*(ch-py);

        double votoCantos = pesoCanto / Math.max(1, distC1) +
                            pesoCanto / Math.max(1, distC2) +
                            pesoCanto / Math.max(1, distC3) +
                            pesoCanto / Math.max(1, distC4);
                            
        riscoMRM += votoCentro + votoCantos;
        
        boolean existeInimigoVivo = false;
        int botsBasicosVivos = 0; 
        
        double riscoShadow = 0;
        
        for (Robo inimigo : listaInimigos.values()) {
            if (!inimigo.vivo) continue;
            existeInimigoVivo = true;
            
            if (inimigo.classificadoComoBasico) botsBasicosVivos++; 
            
            double dxInimigo = px - inimigo.x;
            double dyInimigo = py - inimigo.y;
            double distanciaSqInimigo = dxInimigo*dxInimigo + dyInimigo*dyInimigo;
            double distReal = Math.sqrt(distanciaSqInimigo);
            
            double riscoBase = (1 / Math.max(1, distanciaSqInimigo));
            
            if (distReal < 300) riscoBase *= 10000.0; 
            if (modoFuga) riscoBase *= 3.14; 
            
            riscoBase *= inimigo.fatorAmeaca;

            if (numOthers > 1) {
                boolean isShadowed = false;
                for (Robo escudo : listaInimigos.values()) {
                    if (!escudo.vivo || escudo == inimigo) continue;
                    
                    double dxAB = escudo.x - inimigo.x;
                    double dyAB = escudo.y - inimigo.y;
                    
                    double t = (dxAB * dxInimigo + dyAB * dyInimigo) / Math.max(1.0, distanciaSqInimigo);
                    
                    if (t > 0 && t < 1) { 
                        double projX = inimigo.x + t * dxInimigo;
                        double projY = inimigo.y + t * dyInimigo;
                        
                        double distLinhaSq = (escudo.x - projX)*(escudo.x - projX) + (escudo.y - projY)*(escudo.y - projY);
                        
                        if (distLinhaSq < 1600.0) { 
                            isShadowed = true;
                            break; 
                        }
                    }
                }
                if (isShadowed) {
                    riscoBase *= 0.1; 
                }
            }
            
            double shadowCos = 0;
            if (distRoboP > 0 && distReal > 0) {
                double dotProductShadow = ((px - meuRobo.x) * dxInimigo + (py - meuRobo.y) * dyInimigo);
                shadowCos = Math.abs(dotProductShadow / (distRoboP * distReal));
            }
            riscoShadow += (Math.max(0.1, inimigo.energia) / Math.max(1, distanciaSqInimigo)) * (1.0 + shadowCos);
            
            if (!modoFuga && alvo != null && alvo.vivo && inimigo.nome.equals(alvo.nome)) {
                double baseDist = (numOthers > 1) ? 100.0 : 250.0; 
                double maxDist = (numOthers > 1) ? 400.0 : 700.0;
                
                if (numOthers <= 1 && alvo.classificadoComoBasico && !alvo.classificadoComoSurfer) {
                    baseDist = 100.0; 
                    maxDist = 350.0;  
                }
                
                double distanciaIdeal = Utilitario.limitar(baseDist + (inimigo.agressividade * 25.0), 100.0, maxDist);
                
                double erroDistancia = Math.abs(distReal - distanciaIdeal);
                riscoMRM += (erroDistancia * 35.5); 
                
                if (distanciaIdeal <= 350 && distReal < 300) {
                    riscoBase /= 10000.0; 
                }
                
                boolean isAmeacaAvancada = (inimigo.agressividade > 0.5 || inimigo.saldoPrecisao > 10.0 || inimigo.classificadoComoSurfer);
                
                if (numOthers <= 1 && alvo.classificadoComoBasico && !alvo.classificadoComoSurfer && distReal > 100) {
                    riscoMRM -= 45000.0 / Math.max(1, distReal); 
                }
                
                double raioCaca = (numOthers > 1) ? 800 : 450; 
                if (isAmeacaAvancada && meuRobo.distance(inimigo) <= raioCaca) {
                    if (distReal > 100) { 
                        double forcaAtracao = (numOthers > 1) ? 50000.0 : 25000.0;
                        riscoMRM -= (forcaAtracao * (1.0 + inimigo.agressividade)) / Math.max(1, distReal);
                    }
                }
            }

            double alinhamentoPonto = 0;
            if (distRoboP > 0 && distReal > 0) {
                double dotProduct = ((px - meuRobo.x) * dxInimigo + (py - meuRobo.y) * dyInimigo);
                alinhamentoPonto = Math.abs(dotProduct / (distRoboP * distReal));
            }
            double multiplicadorRota = (1 + alinhamentoPonto);

            double multiplicadorEvasao = 1.0;
            if (alvo != null && alvo.vivo && inimigo.nome.equals(alvo.nome)) {
                double distPAlvo = Math.sqrt((alvo.x - px)*(alvo.x - px) + (alvo.y - py)*(alvo.y - py));
                if (distRoboP > 0 && distPAlvo > 0) {
                    double dx1 = px - meuRobo.x; double dy1 = py - meuRobo.y;
                    double dx2 = alvo.x - px;    double dy2 = alvo.y - py;
                    double cosRelativo = (dx1*dx2 + dy1*dy2) / (distRoboP * distPAlvo);
                    double sinRelativo = (dx1*dy2 - dy1*dx2) / (distRoboP * distPAlvo);
                    multiplicadorEvasao = 1.0 + ((1 - Math.abs(sinRelativo)) + Math.abs(cosRelativo)) / 2.0;
                }
            }
            
            riscoMRM += riscoBase * multiplicadorRota * multiplicadorEvasao;
        }
        
        riscoMRM += riscoShadow * 25000.0; 
        
        double votoTiros = 0;
        long tempoAtePonto = (long)(distRoboP / 8.0); 
        long tempoFuturo = getTime() + tempoAtePonto;
        
        int tirosProcessados = 0;
        for (int i = 0; i < tirosSuspeitos.size(); i++) {
            if (modoShadowLight && tirosProcessados > 0) break;
            tirosProcessados++;
            
            TiroInimigo t = tirosSuspeitos.get(i);
            double distTiroPercurso = (tempoFuturo - t.tempoDisparo) * t.velocidade;
            
            if(distTiroPercurso > 1500) {  
                tirosSuspeitos.remove(i);
                i--;
                continue; 
            }
            
            double posTiroX = t.origem.x + Math.sin(t.angulo) * distTiroPercurso;
            double posTiroY = t.origem.y + Math.cos(t.angulo) * distTiroPercurso;
            
            double distSqTiro = (posTiroX - px)*(posTiroX - px) + (posTiroY - py)*(posTiroY - py);
            
            if(distSqTiro < 2500) { 
                votoTiros += 1050.5 / Math.max(1, distSqTiro);
            }
        }
        riscoMRM += votoTiros;
        
        if (!existeInimigoVivo) {
            riscoMRM += (1.1 + Math.abs(Utilitario.anguloAbsoluto(meuRobo, pontoAlvo) - getHeadingRadians()));
        }

        if (alvo != null && alvo.vivo && alvo.energia < 15 && meuRobo.energia > (alvo.energia * 2.5) && numOthers <= 3) {
            double distPA = Math.sqrt((alvo.x - px)*(alvo.x - px) + (alvo.y - py)*(alvo.y - py));
            riscoMRM -= (125.0 / Math.max(1, distPA)); 
        }
        
        if (alvo != null && alvo.vivo && alvo.classificadoComoSurfer && existeInimigoVivo) {
            double distanciaParaSurfer = Math.sqrt((alvo.x - px)*(alvo.x - px) + (alvo.y - py)*(alvo.y - py));
            if (distanciaParaSurfer > 200) {
                riscoMRM -= (850.0 * alvo.fatorSurf) / Math.max(1, distanciaParaSurfer); 
            } else {
                riscoMRM += (150.0 / Math.max(1, distanciaParaSurfer)); 
            }
        }
        
        if (alvo != null && alvo.vivo) {
            double direcaoRealInimigo = alvo.velocidade < 0 ? alvo.direcao + Math.PI : alvo.direcao;
            
            double vHeadX = Math.sin(direcaoRealInimigo);
            double vHeadY = Math.cos(direcaoRealInimigo);
            double cosInerciaAlvo = 0;
            if (distRoboP > 0) {
                cosInerciaAlvo = ((px - meuRobo.x) * vHeadX + (py - meuRobo.y) * vHeadY) / distRoboP;
            }
            double diferencaAngulo = Math.acos(Utilitario.limitar(cosInerciaAlvo, -1.0, 1.0));
            
            double votoImitacao = -18.5 * (1.0 - (diferencaAngulo / Math.PI)) * (Math.abs(alvo.velocidade) / 8.0);
            riscoMRM += votoImitacao;
        }
        
        double vHeadX = Math.sin(getHeadingRadians());
        double vHeadY = Math.cos(getHeadingRadians());
        double cosInercia = 0;
        if (distRoboP > 0) {
            cosInercia = ((px - meuRobo.x) * vHeadX + (py - meuRobo.y) * vHeadY) / distRoboP;
        }
        
        double fatorCaos = Math.sin((getTime() + meuRobo.x) / 85.5); 
        
        if (fatorCaos > 0.8 && cosInercia > 0.955) { 
            riscoMRM += 35.0; 
        } else if (fatorCaos < -0.8 && cosInercia < 0) { 
            riscoMRM += 35.0; 
        }
        
        ultimoRiscoSurfingAvaliado = riscoSurfing;
        ultimoRiscoMRMAvaliado = riscoMRM;
        
        double pesoS = confiancaSurfing;
        double pesoM = confiancaMRM;
        
        if (numOthers <= 1 && alvo != null && alvo.classificadoComoSurfer) {
            pesoS = 1.0; 
            pesoM = 0.0; 
        } else if (numOthers <= 1 && alvo != null && !alvo.classificadoComoSurfer) {
            pesoS = 0.0; 
            pesoM *= 3.0; 
        } else if (numOthers > 1) { 
            pesoS *= 2.5; 
        }
        
        riscoTotal = (riscoSurfing * pesoS) + (riscoMRM * pesoM);
        
        return riscoTotal;
    }
}