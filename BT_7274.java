package sample;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;

import robocode.*;
import robocode.util.Utils;

/**
 * BT-7274 (Versão Elite 4.7 - MOTOR DE MOVIMENTO "SHADOW" INTEGRADO)
 * Estratégia Híbrida MIRA EXTREMA + Smart Fallback + Wave Surfing
 * * OVERRIDE CIRCULAR REMOVIDO: A IA das Virtual Guns assume novamente o controle total.
 * * SHADOW RAY-CASTING GRID: Geração de pontos de fuga perfeitamente simétricos.
 * * SHADOW RISK METRIC: Equação de risco por Energia/Distância Quadrada implementada p/ evasão fluida.
 * (NENHUM CÓDIGO CORE DE MIRA OU APRENDIZADO FOI ALTERADO)
 */
public class BT_7274 extends AdvancedRobot {
    
    // =========================================================
    // CONSTANTES GLOBAIS OTIMIZADAS
    // =========================================================
    private final int QUANTIDADE_PONTOS_PREVISTOS = 150;
    private final double MARGEM_PAREDE = 22.5; 
    private Random aleatorio = new Random();

    // =========================================================
    // VARIÁVEIS DE ESTADO DO ROBÔ (Isoladas por Instância)
    // =========================================================
    private double potenciaTiroCorrente = 3;
    private double direcaoLateral = 1;
    private double velocidadeInimigoAnterior = 0;
    
    HashMap<String, Robo> listaInimigos = new HashMap<>();
    List<TiroInimigo> tirosSuspeitos = new ArrayList<>();
    
    Robo meuRobo = new Robo();
    Robo alvo;
    
    // Object Pool para Zero-Allocation no Garbage Collector
    List<Point2D.Double> posicoesPossiveis = new ArrayList<>(450);
    int qtdPontosAtivos = 0;
    
    Point2D.Double pontoAlvo = new Point2D.Double(60, 60);
    Rectangle2D.Double campoBatalha = new Rectangle2D.Double();
    
    int tempoInativo = 30;
    boolean modoFuga = false; 
    private Movimento_1VS1 movimento1VS1;
    
    // --- VARIÁVEIS DO VISIT COUNT SURFING E SISTEMA MERITOCRÁTICO ---
    private double energiaInimigoAnterior_1v1 = 100;
    private int[] estatisticasSurfing = new int[47];
    ArrayList<OndaInimiga> ondasInimigas = new ArrayList<>();
    private boolean habilitarSurfing = true;
    
    // Sensor de Imobilidade Global
    private int inimigoTicksParado_1v1 = 0;
    
    // Variáveis do Sistema de Recompensa/Punição
    private double confiancaSurfing = 1.0;
    private double confiancaMRM = 1.0;
    double ultimoRiscoSurfingAvaliado = 0;
    double ultimoRiscoMRMAvaliado = 0;
    double riscoSurfingAlvoAtual = 0;
    double riscoMRMAlvoAtual = 0;

    // Constantes Estáticas do GuessFactor
    private static final int INDICES_DISTANCIA = 6;
    private static final int INDICES_VELOCIDADE = 6;
    private static final int BINS = 47; 
    private static final int BIN_CENTRAL = (BINS - 1) / 2;
    private static final double ANGULO_ESCAPE_MAXIMO = 0.7;
    private static final double LARGURA_BIN = ANGULO_ESCAPE_MAXIMO / (double) BIN_CENTRAL;
    
    // Buffer Isolado por Instância (Previne Cross-Talk em torneios Melee)
    private final int[][][][] buffersEstatisticos = new int[INDICES_DISTANCIA][INDICES_VELOCIDADE][INDICES_VELOCIDADE][BINS];
    
    // Inicialização da Pool de Memória
    public BT_7274() {
        movimento1VS1 = new Movimento_1VS1(this);
        for (int i = 0; i < 450; i++) {
            posicoesPossiveis.add(new Point2D.Double());
        }
    }

    // =========================================================
    // CLASSES AUXILIARES E ESTRUTURAS DE DADOS
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
        public int ticksParado = 0; 
        
        public double pesoAprendizadoDinâmico = 1.0;
        
        public LinkedList<java.lang.Double> historicoVelocidade = new LinkedList<>();
        public LinkedList<java.lang.Double> historicoDeltaDirecao = new LinkedList<>();
        
        // --- VIRTUAL GUNS ARRAY ---
        public double[] acertosVirtualGuns = new double[7];
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
    // VISIT COUNT SURFING (WAVE SURFING DEFENSIVO)
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

    private double preverRiscoMovimento(OndaInimiga onda, int direcao) {
        double posPrevistaX = meuRobo.x;
        double posPrevistaY = meuRobo.y;
        double velocidadeSimulada = getVelocity();
        double direcaoSimulada = getHeadingRadians();
        
        long tempoVoo = (long) ((onda.origem.distance(meuRobo) - ((getTime() - onda.tempoDisparo) * onda.velocidadeBala)) / onda.velocidadeBala);

        for (int i = 0; i < Math.max(1, tempoVoo); i++) {
            if (direcao == 0) {
                velocidadeSimulada = Math.abs(velocidadeSimulada) > 2 ? velocidadeSimulada * 0.5 : 0; 
            } else {
                velocidadeSimulada = Utilitario.limitar(velocidadeSimulada + (direcao > 0 ? 1 : -1), -8, 8);
            }
            
            direcaoSimulada = Math.atan2(posPrevistaX - onda.origem.x, posPrevistaY - onda.origem.y) + (Math.PI/2) * (direcao == 0 ? 1 : direcao);
            
            posPrevistaX += Math.sin(direcaoSimulada) * velocidadeSimulada;
            posPrevistaY += Math.cos(direcaoSimulada) * velocidadeSimulada;

            posPrevistaX = Utilitario.limitar(posPrevistaX, MARGEM_PAREDE, campoBatalha.width - MARGEM_PAREDE);
            posPrevistaY = Utilitario.limitar(posPrevistaY, MARGEM_PAREDE, campoBatalha.height - MARGEM_PAREDE);
        }

        int bin = onda.obterBin(posPrevistaX, posPrevistaY);
        double risco = 0;
        for (int i = -2; i <= 2; i++) {
            int binAvaliado = (int) Utilitario.limitar(bin + i, 0, OndaInimiga.BINS_SURF - 1);
            risco += estatisticasSurfing[binAvaliado] * (1.0 / (Math.abs(i) + 1));
        }
        return risco;
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
    // LÓGICA DE TIRO 1 VS 1 (GUESSFACTOR + VIRTUAL GUNS)
    // =========================================================
    class Onda extends Condition {
        Point2D posicaoAlvo; 
        public Robo alvoOnda; 
        double potenciaTiro;
        Point2D posicaoCanhao;
        double angulo;
        double direcaoLateralOnda;
        
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
        public int binVotoCircular = -1;

        private final AdvancedRobot robô;

        Onda(AdvancedRobot _robô) {
            this.robô = _robô;
        }
        
        public boolean test() {
            distanciaPercorrida += Rules.getBulletSpeed(potenciaTiro);
            if (distanciaPercorrida > posicaoCanhao.distance(posicaoAlvo) - MARGEM_PAREDE) {
                int binCorreto = (int) Math.round((Utils.normalRelativeAngle(Utilitario.anguloAbsoluto(posicaoCanhao, posicaoAlvo) - angulo) / (direcaoLateralOnda * LARGURA_BIN)) + BIN_CENTRAL);
                binCorreto = (int) Utilitario.limitar(binCorreto, 0, BINS - 1);
                
                int pesoBase = (int)Math.round(10 * pesoImpacto);
                
                for (int i = 0; i < BINS; i++) {
                    double distanciaBin = Math.abs(binCorreto - i);
                    if (distanciaBin <= 5) { 
                        buffer[i] += (int) Math.round(pesoBase / (Math.pow(2, distanciaBin)));
                    }
                }
                
                if (alvoOnda != null) {
                    if (binVotoAuxiliar != -1 && Math.abs(binCorreto - binVotoAuxiliar) <= 2) alvoOnda.acertosVirtualGuns[0]++;
                    if (binVotoARMA != -1 && Math.abs(binCorreto - binVotoARMA) <= 2) alvoOnda.acertosVirtualGuns[1]++;
                    if (binVotoARIMA != -1 && Math.abs(binCorreto - binVotoARIMA) <= 2) alvoOnda.acertosVirtualGuns[2]++;
                    if (binVotoRNA != -1 && Math.abs(binCorreto - binVotoRNA) <= 2) alvoOnda.acertosVirtualGuns[3]++;
                    if (binVotoDC != -1 && Math.abs(binCorreto - binVotoDC) <= 2) alvoOnda.acertosVirtualGuns[4]++;
                    if (binVotoAntiTremidinha != -1 && Math.abs(binCorreto - binVotoAntiTremidinha) <= 2) alvoOnda.acertosVirtualGuns[5]++;
                    if (binVotoMedia != -1 && Math.abs(binCorreto - binVotoMedia) <= 2) alvoOnda.acertosVirtualGuns[6]++;
                    
                    for (int j = 0; j < 7; j++) {
                        alvoOnda.acertosVirtualGuns[j] *= 0.95;
                    }
                }

                robô.removeCustomEvent(this);
            }
            return false;
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
            
            double offsetCircular = Utils.normalRelativeAngle(angulo3 - angulo);
            binVotoCircular = (int) Math.round((offsetCircular / (direcaoLateralOnda * LARGURA_BIN)) + BIN_CENTRAL);
            binVotoCircular = (int) Utilitario.limitar(binVotoCircular, 0, BINS - 1);
            
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
            
            int melhorVG = -1;
            double maxAcertosVG = 1.5; 
            if (bot.alvo != null && bot.getOthers() <= 1) {
                for (int j = 0; j < 7; j++) {
                    if (bot.alvo.acertosVirtualGuns[j] > maxAcertosVG) {
                        maxAcertosVG = bot.alvo.acertosVirtualGuns[j];
                        melhorVG = j;
                    }
                }
            }
            
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
            Robo inimigo = listaInimigos.get(e.getName());
            if (inimigo == null) {
                inimigo = new Robo();
                listaInimigos.put(e.getName(), inimigo);
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
            
            if (isClinger || isLinear) {
                inimigo.classificadoComoBasico = true;
            } else {
                inimigo.classificadoComoBasico = false;
            }
            
            double velLateral = inimigo.velocidade * Math.sin(inimigo.direcao - (getHeadingRadians() + inimigo.anguloAbsolutoRadianos));
            if (Math.abs(velLateral) > 0.1 && Math.abs(inimigo.ultimaVelocidadeLateral) > 0.1) {
                if (Utilitario.sinal(velLateral) != Utilitario.sinal(inimigo.ultimaVelocidadeLateral)) {
                    inimigo.reversoesLaterais++;
                }
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
            } else if (inimigo.fatorSurf == 0.0) {
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
        }
        else {
            setScanColor(Color.red);
            Robo inimigo = new Robo();
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
            
            if (isClinger || isLinear) {
                inimigo.classificadoComoBasico = true;
            } else {
                inimigo.classificadoComoBasico = false;
            }
            
            if (inimigo.velocidade != 0) {
                direcaoLateral = Utilitario.sinal(inimigo.velocidade * Math.sin(e.getHeadingRadians() - inimigo.anguloAbsolutoRadianos));
            }
                
            Onda onda = new Onda(this);
            onda.posicaoCanhao = new Point2D.Double(getX(), getY());
            onda.posicaoAlvo = inimigo;
            onda.direcaoLateralOnda = direcaoLateral;
            onda.definirSegmentacoes(inimigo.distancia, inimigo.velocidade, velocidadeInimigoAnterior);
            
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
                if (getEnergy() >= onda.potenciaTiro) {
                    setFire(onda.potenciaTiro);
                }
            } else {
                double tempoEstimadoVoo = inimigo.distancia / Rules.getBulletSpeed(onda.potenciaTiro);
                onda.registrarMirasAuxiliares(inimigo, meuRobo, tempoEstimadoVoo);
                onda.registrarMirasARMA_ARIMA(inimigo, meuRobo, tempoEstimadoVoo, getOthers());
                
                setTurnGunRightRadians(Utils.normalRelativeAngle(
                        inimigo.anguloAbsolutoRadianos - getGunHeadingRadians() + onda.offsetAnguloMaisVisitado()));
                        
                setFire(onda.potenciaTiro);
                
                if (getEnergy() >= onda.potenciaTiro) {
                    onda.alvoOnda = inimigo; 
                    addCustomEvent(onda);
                }
            }
            
            double quedaEnergia = energiaInimigoAnterior_1v1 - e.getEnergy();
            if (quedaEnergia > 0 && quedaEnergia <= 3.0) {
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
                    executarSurfing(); 
                }
            }

            double anguloRadar = Utils.normalRelativeAngle(inimigo.anguloAbsolutoRadianos - getRadarHeadingRadians());
            setTurnRadarRightRadians(anguloRadar * 2.0);
        }
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
        Robo inimigo = listaInimigos.get(e.getName());
        if (inimigo != null) {
            inimigo.saldoPrecisao += 15.0; 
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
    
    public void onWin(WinEvent event) {
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
    // LÓGICA DE DISPARO (MELEE PREDITIVO)
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
                setFire(potencia);
            }
            
            setTurnGunRightRadians(Utils.normalRelativeAngle(
                ((Math.PI / 2) - Math.atan2(mirarEm.y - meuRobo.getY(), mirarEm.x - meuRobo.getX())) - getGunHeadingRadians()
            ));
        }
    }

    // =========================================================
    // LÓGICA DE MOVIMENTO MINIMUM RISK (COM SHADOW GRID & RISK)
    // =========================================================
    public void movimento() {
        if (pontoAlvo.distance(meuRobo) < 15 || tempoInativo > 25) {
            tempoInativo = 0;
            
            // Alterado para gerar 144 pontos em grid simétrico (estilo Shadow) em vez de aleatórios
            int pontosAmostragem = (getOthers() > 1) ? 144 : 36;
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
            
            setMaxVelocity(maxVel);
            setAhead(meuRobo.distance(pontoAlvo) * direcao);
            
            angulo = Utils.normalRelativeAngle(angulo);
            setTurnRightRadians(angulo);
        }
    }

    // =======================================================
    // NOVO: SHADOW RAY-CASTING GRID (Geração Estruturada)
    // =======================================================
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

        if (alvo != null && alvo.vivo && alvo.classificadoComoBasico) {
            double distParaAlvo = p.distance(alvo);
            double desvioOrbita = Math.abs(distParaAlvo - 450.0); 
            riscoMRM += (desvioOrbita * 35.0); 
        }

        if (habilitarSurfing && !ondasInimigas.isEmpty()) {
            double votoSurfing = 0;
            for (OndaInimiga onda : ondasInimigas) {
                int binDoPonto = onda.obterBin(px, py);
                
                double riscoOnda = 0;
                for (int i = -2; i <= 2; i++) {
                    int binAvaliado = (int) Utilitario.limitar(binDoPonto + i, 0, OndaInimiga.BINS_SURF - 1);
                    riscoOnda += estatisticasSurfing[binAvaliado] * (1.0 / (Math.abs(i) + 1));
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
        
        // =======================================================
        // LÓGICA DE RISCO SHADOW INTEGRADA
        // =======================================================
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
            
            // Shadow Risk Metric: Pondera a periculosidade por energia e penaliza andar na direção das balas (Cosseno)
            double shadowCos = 0;
            if (distRoboP > 0 && distReal > 0) {
                double dotProductShadow = ((px - meuRobo.x) * dxInimigo + (py - meuRobo.y) * dyInimigo);
                shadowCos = Math.abs(dotProductShadow / (distRoboP * distReal));
            }
            riscoShadow += (Math.max(0.1, inimigo.energia) / Math.max(1, distanciaSqInimigo)) * (1.0 + shadowCos);
            
            if (!modoFuga && alvo != null && alvo.vivo && inimigo.nome.equals(alvo.nome)) {
                double baseDist = (numOthers > 1) ? 100.0 : 250.0; 
                double maxDist = (numOthers > 1) ? 400.0 : 700.0;
                double distanciaIdeal = Utilitario.limitar(baseDist + (inimigo.agressividade * 25.0), 100.0, maxDist);
                
                double erroDistancia = Math.abs(distReal - distanciaIdeal);
                riscoMRM += (erroDistancia * 35.5);
                
                if (distanciaIdeal <= 350 && distReal < 300) {
                    riscoBase /= 10000.0; 
                }
                
                boolean isAmeacaAvancada = (inimigo.agressividade > 0.5 || inimigo.saldoPrecisao > 10.0 || inimigo.classificadoComoSurfer);
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
        
        // Multiplicador do Risco Shadow normalizado com o risco existente
        riscoMRM += riscoShadow * 25000.0; 
        // =======================================================
        
        double votoTiros = 0;
        long tempoAtePonto = (long)(distRoboP / 8.0); 
        long tempoFuturo = getTime() + tempoAtePonto;
        
        for (int i = 0; i < tirosSuspeitos.size(); i++) {
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
        
        riscoTotal = (riscoSurfing * confiancaSurfing) + (riscoMRM * confiancaMRM);
        
        return riscoTotal;
    }
}


