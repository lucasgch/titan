package sample;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;

import robocode.*;
import robocode.util.Utils;

/**
 * BT-7274 (Versão Original - Edição Definitiva 2.0)
 * Estratégia Híbrida MIRA EXTREMA + Smart Fallback + Wave Surfing
 * * CORREÇÃO DA TREMEDEIRA APLICADA
 * * TOLERÂNCIA DE "SOLUÇOS" APLICADA AO SENSOR DE IMOBILIDADE
 * (NENHUM CÓDIGO CORE ORIGINAL FOI REMOVIDO)
 */
public class BT_7274 extends AdvancedRobot {
    
    // =========================================================
    // CONSTANTES GLOBAIS OTIMIZADAS
    // =========================================================
    static double POTENCIA_TIRO = 3;
    static final int QUANTIDADE_PONTOS_PREVISTOS = 150;
    static final double MARGEM_PAREDE = 22.5; 
    static Random aleatorio = new Random();

    // =========================================================
    // VARIÁVEIS DE ESTADO DO ROBÔ
    // =========================================================
    HashMap<String, Robo> listaInimigos = new HashMap<>();
    List<TiroInimigo> tirosSuspeitos = new ArrayList<>();
    
    Robo meuRobo = new Robo();
    Robo alvo;
    
    List<Point2D.Double> posicoesPossiveis = new ArrayList<>();
    Point2D.Double pontoAlvo = new Point2D.Double(60, 60);
    Rectangle2D.Double campoBatalha = new Rectangle2D.Double();
    
    int tempoInativo = 30;
    boolean modoFuga = false; 
    private static double direcaoLateral;
    private static double velocidadeInimigoAnterior;
    private Movimento_1VS1 movimento1VS1;
    
    // --- VARIÁVEIS DO VISIT COUNT SURFING E SISTEMA MERITOCRÁTICO ---
    static double energiaInimigoAnterior_1v1 = 100;
    static int[] estatisticasSurfing = new int[47];
    ArrayList<OndaInimiga> ondasInimigas = new ArrayList<>();
    static boolean habilitarSurfing = true;
    
    // Sensor de Imobilidade Global (Modificação Definitiva)
    static int inimigoTicksParado_1v1 = 0;
    
    // Variáveis do Sistema de Recompensa/Punição
    static double confiancaSurfing = 1.0;
    static double confiancaMRM = 1.0;
    double ultimoRiscoSurfingAvaliado = 0;
    double ultimoRiscoMRMAvaliado = 0;
    double riscoSurfingAlvoAtual = 0;
    double riscoMRMAlvoAtual = 0;
    
    // Inicializador de instâncias
    {
        movimento1VS1 = new Movimento_1VS1(this);
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
        public int ticksParado = 0; // Sensor para Melee
        
        public double pesoAprendizadoDinâmico = 1.0;
        
        // Histórico para Análise de Séries Temporais (ARMA/ARIMA)
        public LinkedList<java.lang.Double> historicoVelocidade = new LinkedList<>();
        public LinkedList<java.lang.Double> historicoDeltaDirecao = new LinkedList<>();
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

        public boolean checarAcerto(Point2D.Double posicaoRobo, long tempoAtual) {
            double distanciaPercorrida = (tempoAtual - tempoDisparo) * velocidadeBala;
            if (distanciaPercorrida > origem.distance(posicaoRobo) - 18) {
                int bin = obterBin(posicaoRobo);
                estatisticasSurfing[bin]++; 
                return true;
            }
            return false;
        }

        public int obterBin(Point2D.Double alvoPos) {
            double anguloDesejado = Utilitario.anguloAbsoluto(origem, alvoPos);
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
            if (onda.checarAcerto(meuRobo, getTime())) {
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
        Point2D.Double posPrevista = (Point2D.Double) meuRobo.clone();
        double velocidadeSimulada = getVelocity();
        double direcaoSimulada = getHeadingRadians();
        
        long tempoVoo = (long) ((onda.origem.distance(meuRobo) - ((getTime() - onda.tempoDisparo) * onda.velocidadeBala)) / onda.velocidadeBala);

        for (int i = 0; i < Math.max(1, tempoVoo); i++) {
            if (direcao == 0) {
                velocidadeSimulada = Math.abs(velocidadeSimulada) > 2 ? velocidadeSimulada * 0.5 : 0; 
            } else {
                velocidadeSimulada = Utilitario.limitar(velocidadeSimulada + (direcao > 0 ? 1 : -1), -8, 8);
            }
            
            direcaoSimulada = Utilitario.anguloAbsoluto(onda.origem, posPrevista) + (Math.PI/2) * (direcao == 0 ? 1 : direcao);
            
            posPrevista.x += Math.sin(direcaoSimulada) * velocidadeSimulada;
            posPrevista.y += Math.cos(direcaoSimulada) * velocidadeSimulada;

            posPrevista.x = Utilitario.limitar(posPrevista.x, MARGEM_PAREDE, campoBatalha.width - MARGEM_PAREDE);
            posPrevista.y = Utilitario.limitar(posPrevista.y, MARGEM_PAREDE, campoBatalha.height - MARGEM_PAREDE);
        }

        int bin = onda.obterBin(posPrevista);
        
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
                
            if ((Math.random() < (Rules.getBulletSpeed(POTENCIA_TIRO) / AJUSTE_REVERSA) / inimigo.distancia ||
                    tempoTentativa > (inimigo.distancia / Rules.getBulletSpeed(POTENCIA_TIRO) / AJUSTE_QUIQUE_PAREDE))) {
                direcao = -direcao;
            }
                
            double angulo = Utilitario.anguloAbsoluto(posicaoRobo, destinoRobo) - robô.getHeadingRadians();
            robô.setAhead(Math.cos(angulo) * 100);
            robô.setTurnRightRadians(Math.tan(angulo));
        }
    }

    // =========================================================
    // LÓGICA DE TIRO 1 VS 1 (GUESSFACTOR + KERNEL + AUXILIARES + ARMA + ARIMA)
    // =========================================================
    static class Onda extends Condition {
        static Point2D posicaoAlvo;
        double potenciaTiro;
        Point2D posicaoCanhao;
        double angulo;
        double direcaoLateral;
        
        private static final double DISTANCIA_MAXIMA = 900;
        private static final int INDICES_DISTANCIA = 6;
        private static final int INDICES_VELOCIDADE = 6;
        private static final int BINS = 47; 
        private static final int BIN_CENTRAL = (BINS - 1) / 2;
        private static final double ANGULO_ESCAPE_MAXIMO = 0.7;
        private static final double LARGURA_BIN = ANGULO_ESCAPE_MAXIMO / (double) BIN_CENTRAL; 
        
        private static final int[][][][] buffersEstatisticos = new int[INDICES_DISTANCIA][INDICES_VELOCIDADE][INDICES_VELOCIDADE][BINS];
        private int[] buffer;
        private double distanciaPercorrida;
        private final AdvancedRobot robô;
        public double pesoImpacto = 5.0; 
        
        public int binVotoAuxiliar = -1; 
        public int binVotoARMA = -1;
        public int binVotoARIMA = -1;
        
        Onda(AdvancedRobot _robô) {
            this.robô = _robô;
        }
        
        public boolean test() {
            avancar();
            if (chegou()) {
                int binCorreto = binAtual();
                int pesoBase = (int)Math.round(10 * pesoImpacto);
                
                for (int i = 0; i < BINS; i++) {
                    double distanciaBin = Math.abs(binCorreto - i);
                    if (distanciaBin <= 5) { 
                        buffer[i] += (int) Math.round(pesoBase / (Math.pow(2, distanciaBin)));
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
            
            double mediaSeno = (Math.sin(angulo1) + Math.sin(angulo2) + Math.sin(angulo3)) / 3.0;
            double mediaCosseno = (Math.cos(angulo1) + Math.cos(angulo2) + Math.cos(angulo3)) / 3.0;
            double anguloMedio = Math.atan2(mediaSeno, mediaCosseno);
            
            double offsetMedio = Utils.normalRelativeAngle(anguloMedio - angulo);
            int binMedia = (int) Math.round((offsetMedio / (direcaoLateral * LARGURA_BIN)) + BIN_CENTRAL);
            
            binVotoAuxiliar = (int) Utilitario.limitar(binMedia, 0, BINS - 1);
        }

        public void registrarMirasARMA_ARIMA(Robo inimigo, Robo meuRobo, double tempoEstimado, int qtdInimigosVivos) {
            if (inimigo.historicoVelocidade.size() < 5) return; 
            
            int profundidadeMaxima = (qtdInimigosVivos > 2) ? 5 : ((qtdInimigosVivos == 2) ? 15 : 40);
            int n = Math.min(inimigo.historicoVelocidade.size(), profundidadeMaxima);
            
            double iniX = meuRobo.x + inimigo.distancia * Math.sin(inimigo.anguloAbsolutoRadianos);
            double iniY = meuRobo.y + inimigo.distancia * Math.cos(inimigo.anguloAbsolutoRadianos);
            
            // --- SIMULAÇÃO ARMA ---
            double mediaVel = 0, mediaDeltaDir = 0;
            for(int i = 0; i < n; i++) {
                mediaVel += inimigo.historicoVelocidade.get(i);
                mediaDeltaDir += inimigo.historicoDeltaDirecao.get(i);
            }
            mediaVel /= n;
            mediaDeltaDir /= n;
            
            double simArmaX = iniX;
            double simArmaY = iniY;
            double simArmaDir = inimigo.direcao;
            
            for(int t = 0; t < tempoEstimado; t++) {
                simArmaDir += mediaDeltaDir;
                simArmaX += Math.sin(simArmaDir) * mediaVel;
                simArmaY += Math.cos(simArmaDir) * mediaVel;
            }
            double anguloARMA = Utilitario.anguloAbsoluto(meuRobo, new Point2D.Double(simArmaX, simArmaY));
            int binARMA = (int) Math.round((Utils.normalRelativeAngle(anguloARMA - angulo) / (direcaoLateral * LARGURA_BIN)) + BIN_CENTRAL);
            binVotoARMA = (int) Utilitario.limitar(binARMA, 0, BINS - 1);
            
            // --- SIMULAÇÃO ARIMA ---
            double aceleracaoMedia = 0, deltaDaDeltaDir = 0;
            for(int i = 0; i < n - 1; i++) {
                aceleracaoMedia += (inimigo.historicoVelocidade.get(i) - inimigo.historicoVelocidade.get(i+1));
                deltaDaDeltaDir += (inimigo.historicoDeltaDirecao.get(i) - inimigo.historicoDeltaDirecao.get(i+1));
            }
            aceleracaoMedia /= Math.max(1, n - 1);
            deltaDaDeltaDir /= Math.max(1, n - 1);
            
            double simArimaX = iniX;
            double simArimaY = iniY;
            double simArimaVel = inimigo.velocidade;
            double simArimaDir = inimigo.direcao;
            double simArimaDeltaDir = inimigo.direcao - inimigo.ultimaDirecao;
            
            for(int t = 0; t < tempoEstimado; t++) {
                simArimaVel = Utilitario.limitar(simArimaVel + aceleracaoMedia, -8, 8); 
                simArimaDeltaDir += deltaDaDeltaDir; 
                simArimaDir += simArimaDeltaDir;
                
                simArimaX += Math.sin(simArimaDir) * simArimaVel;
                simArimaY += Math.cos(simArimaDir) * simArimaVel;
            }
            double anguloARIMA = Utilitario.anguloAbsoluto(meuRobo, new Point2D.Double(simArimaX, simArimaY));
            int binARIMA = (int) Math.round((Utils.normalRelativeAngle(anguloARIMA - angulo) / (direcaoLateral * LARGURA_BIN)) + BIN_CENTRAL);
            binVotoARIMA = (int) Utilitario.limitar(binARIMA, 0, BINS - 1);
        }
        
        double offsetAnguloMaisVisitado() {
            return (direcaoLateral * LARGURA_BIN) * (binMaisVisitado() - BIN_CENTRAL);
        }
        
        void definirSegmentacoes(double distancia, double velocidade, double ultimaVelocidade) {
            int indiceDistancia = (int) Math.min(INDICES_DISTANCIA - 1, distancia / (DISTANCIA_MAXIMA / INDICES_DISTANCIA));
            int indiceVelocidade = (int) Math.min(INDICES_VELOCIDADE - 1, Math.abs(velocidade / 2));
            int indiceUltimaVelocidade = (int) Math.min(INDICES_VELOCIDADE - 1, Math.abs(ultimaVelocidade / 2));
            buffer = buffersEstatisticos[indiceDistancia][indiceVelocidade][indiceUltimaVelocidade];
        }
        
        private void avancar() {
            distanciaPercorrida += Rules.getBulletSpeed(potenciaTiro);
        }
        
        private boolean chegou() {
            return distanciaPercorrida > posicaoCanhao.distance(posicaoAlvo) - MARGEM_PAREDE;
        }
        
        private int binAtual() {
            int bin = (int) Math.round((Utils.normalRelativeAngle(Utilitario.anguloAbsoluto(posicaoCanhao, posicaoAlvo) - angulo) / (direcaoLateral * LARGURA_BIN)) + BIN_CENTRAL);
            return (int) Utilitario.limitar(bin, 0, BINS - 1);
        }
        
        private int binMaisVisitado() {
            int maisVisitado = BIN_CENTRAL;
            double maiorVoto = -1; 
            BT_7274 bot = (BT_7274) robô;
            
            for (int i = 0; i < BINS; i++) {
                double votos = buffer[i];
                
                if (i == binVotoAuxiliar) votos += (10.0 * pesoImpacto); 
                
                // Condição para ARIMA e ARMA assumirem o controle caso seja um bot básico
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
            return maisVisitado;
        }
    }
    
    // =========================================================
    // ESTÉTICA E CORES
    // =========================================================
    private void coresBT7274() {
        setColors(new Color(60, 80, 40), new Color(255, 120, 0), new Color(100, 100, 100), 
                  new Color(255, 120, 0), new Color(255, 120, 0));
    }
    
    private void corVitoria() {
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
            atualizarListaPosicoes(QUANTIDADE_PONTOS_PREVISTOS);
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
        }
        else {
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
            
            // Monitorização de Imobilidade (Melee)
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
            
            // Heurística para classificar robôs básicos no MELEE (Clingers ou Lineares)
            double distParedeInimigo = Math.min(
                Math.min(inimigo.x, campoBatalha.width - inimigo.x), 
                Math.min(inimigo.y, campoBatalha.height - inimigo.y)
            );
            boolean isClinger = distParedeInimigo < 60; // Gosta de colar na parede
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
            
            // MODIFICAÇÃO DEFINITIVA: SENSOR DE IMOBILIDADE 1V1 (Ajustado para tolerar soluços)
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
            
            // Heurística para classificar robôs básicos no 1V1
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
            Onda.posicaoAlvo = inimigo;
            onda.direcaoLateral = direcaoLateral;
            onda.definirSegmentacoes(inimigo.distancia, inimigo.velocidade, velocidadeInimigoAnterior);
            
            if(alvo != null && alvo.saldoPrecisao > 5) {
                onda.pesoImpacto = 12.0; 
            }
            
            velocidadeInimigoAnterior = inimigo.velocidade;
            onda.angulo = inimigo.anguloAbsolutoRadianos;
            
            POTENCIA_TIRO = Math.min(3, Math.min(this.getEnergy(), e.getEnergy()) / (double) 4);
            if (getEnergy() < 2 && e.getDistance() < 500) {
                POTENCIA_TIRO = 0.1;
            } else if (e.getDistance() >= 500) {
                POTENCIA_TIRO = 1.1;
            }
            if (inimigo.distancia < 150) {
                POTENCIA_TIRO = Math.min(3.0, getEnergy() / 12); 
            }
            if (alvo != null && alvo.saldoPrecisao > 40.0) {
                POTENCIA_TIRO = Math.max(POTENCIA_TIRO, 2.9);
            }
            onda.potenciaTiro = POTENCIA_TIRO;
            
            // MODIFICAÇÃO DEFINITIVA: OVERRIDE DA MIRA (Matador de Alvo Travado)
            if (inimigoTicksParado_1v1 > 5) {
                // Bypass na predição. Atira direto na testa.
                setTurnGunRightRadians(Utils.normalRelativeAngle(inimigo.anguloAbsolutoRadianos - getGunHeadingRadians()));
                if (getEnergy() >= onda.potenciaTiro) {
                    setFire(onda.potenciaTiro);
                }
            } else {
                // LÓGICA DE TIRO ORIGINAL (MANTIDA)
                double tempoEstimadoVoo = inimigo.distancia / Rules.getBulletSpeed(onda.potenciaTiro);
                onda.registrarMirasAuxiliares(inimigo, meuRobo, tempoEstimadoVoo);
                onda.registrarMirasARMA_ARIMA(inimigo, meuRobo, tempoEstimadoVoo, getOthers());
                
                setTurnGunRightRadians(Utils.normalRelativeAngle(
                        inimigo.anguloAbsolutoRadianos - getGunHeadingRadians() + onda.offsetAnguloMaisVisitado()));
                        
                setFire(onda.potenciaTiro);
                
                if (getEnergy() >= onda.potenciaTiro) {
                    addCustomEvent(onda);
                }
            }
            
            // --- DETECÇÃO DE ONDAS INIMIGAS (SURFING) ---
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
                
            // MODIFICAÇÃO DEFINITIVA 2.0: MOVIMENTO HÍBRIDO SEM TREMEDEIRA
            movimento1VS1.onScannedRobot(e); // Calcula a órbita primeiro
            
            if (inimigo.distancia < 250) {
                // Em vez de dar uma travagem brusca, ele apenas "abre" o ângulo de órbita para se afastar suavemente
                setTurnRightRadians(Utils.normalRelativeAngle(inimigo.anguloAbsolutoRadianos + Math.PI/2 - 0.5));
                setAhead(100);
            } else {
                if (habilitarSurfing) {
                    executarSurfing(); // Só surfa se estiver a uma distância segura
                }
            }

            // MODIFICAÇÃO DEFINITIVA: RADAR INQUEBRÁVEL 
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
        if (event.getName().equals(alvo.nome)) {
            alvo.vivo = false;
        }
    }
    
    public void onWin(WinEvent event) {
        while (true) {
            corVitoria();
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
            
            // MODIFICAÇÃO DEFINITIVA (Melee): Não tenta prever se o alvo estiver imóvel
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
    // LÓGICA DE MOVIMENTO MINIMUM RISK 
    // =========================================================
    public void movimento() {
        if (pontoAlvo.distance(meuRobo) < 15 || tempoInativo > 25) {
            tempoInativo = 0;
            atualizarListaPosicoes(QUANTIDADE_PONTOS_PREVISTOS);
            
            Point2D.Double pontoMenorRisco = null;
            double menorRisco = Double.MAX_VALUE;
            double melhorRiscoSurf = 0;
            double melhorRiscoMRM = 0;
            
            for (Point2D.Double p : posicoesPossiveis) {
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
            
            setMaxVelocity(10 - (4 * Math.abs(getTurnRemainingRadians())));
            setAhead(meuRobo.distance(pontoAlvo) * direcao);
            
            angulo = Utils.normalRelativeAngle(angulo);
            setTurnRightRadians(angulo);
        }
    }

    public void atualizarListaPosicoes(int n) {
        posicoesPossiveis.clear();
        final int alcanceX = (int) (125 * 1.5);
        
        for (int i = 0; i < n; i++) {
            double modX = Utilitario.aleatorioEntre(-alcanceX, alcanceX);
            double alcanceY = Math.sqrt(alcanceX * alcanceX - modX * modX);
            double modY = Utilitario.aleatorioEntre(-alcanceY, alcanceY);
            
            double y = Utilitario.limitar(meuRobo.y + modY, 75, campoBatalha.height - 75);
            double x = Utilitario.limitar(meuRobo.x + modX, 75, campoBatalha.width - 75);
            
            posicoesPossiveis.add(new Point2D.Double(x, y));
        }
    }
    
    public double avaliarPonto(Point2D.Double p) {
        double riscoTotal = 0;
        double riscoSurfing = 0;
        double riscoMRM = 0;
        
        if (alvo != null && alvo.vivo && alvo.classificadoComoBasico) {
            double distParaAlvo = p.distance(alvo);
            double desvioOrbita = Math.abs(distParaAlvo - 450.0); 
            riscoMRM += (desvioOrbita * 35.0); // Punição aumentada para forçar a distância
        }

        if (habilitarSurfing && !ondasInimigas.isEmpty()) {
            double votoSurfing = 0;
            for (OndaInimiga onda : ondasInimigas) {
                int binDoPonto = onda.obterBin(p);
                
                double riscoOnda = 0;
                for (int i = -2; i <= 2; i++) {
                    int binAvaliado = (int) Utilitario.limitar(binDoPonto + i, 0, OndaInimiga.BINS_SURF - 1);
                    riscoOnda += estatisticasSurfing[binAvaliado] * (1.0 / (Math.abs(i) + 1));
                }
                
                double distVoo = (getTime() - onda.tempoDisparo) * onda.velocidadeBala;
                double distRestante = onda.origem.distance(p) - distVoo;
                
                if (distRestante > 0) {
                    votoSurfing += (riscoOnda * 8500.0) / Math.max(1, distRestante);
                }
            }
            riscoSurfing += votoSurfing;
        }
        
        double votoEstabilidade = Utilitario.aleatorioEntre(1.15, 2.42) / p.distanceSq(meuRobo);
        riscoMRM += votoEstabilidade;
        
        double fatorMultidao = (6.85 * Math.max(0, getOthers() - 1));
        double votoCentro = fatorMultidao / p.distanceSq(campoBatalha.width / 2, campoBatalha.height / 2);
        
        double pesoCanto = getOthers() <= 5 ? (getOthers() == 1 ? 0.32 : 0.58) : 1.15;
        
        if (modoFuga) {
            pesoCanto *= 0.12;
            votoCentro = 0;
        }
        
        double votoCantos = pesoCanto / p.distanceSq(0, 0) +
                            pesoCanto / p.distanceSq(campoBatalha.width, 0) +
                            pesoCanto / p.distanceSq(0, campoBatalha.height) +
                            pesoCanto / p.distanceSq(campoBatalha.width, campoBatalha.height);
                            
        riscoMRM += votoCentro + votoCantos;
        
        boolean existeInimigoVivo = false;
        Iterator<Robo> iteradorInimigos = listaInimigos.values().iterator();
        
        while (iteradorInimigos.hasNext()) {
            Robo inimigo = iteradorInimigos.next();
            if (!inimigo.vivo) continue;
            existeInimigoVivo = true;
            
            double distanciaSqInimigo = p.distanceSq(inimigo);
            double riscoBase = (1 / Math.max(1, distanciaSqInimigo));
            
            // MODIFICAÇÃO DEFINITIVA: ANTI-RAMMING SEVERO (Melee)
            double distReal = p.distance(inimigo);
            if (distReal < 300) {
                riscoBase *= 10000.0; // Parede repulsiva intransponível
            }
            
            if (modoFuga) riscoBase *= 3.14; 
            
            riscoBase *= inimigo.fatorAmeaca;
            
            double alinhamentoPonto = Math.abs(Math.cos(Utilitario.anguloAbsoluto(meuRobo, p) - Utilitario.anguloAbsoluto(inimigo, p)));
            double multiplicadorRota = (1 + alinhamentoPonto);

            double multiplicadorEvasao = 1.0;
            if (alvo != null && alvo.vivo && inimigo.nome.equals(alvo.nome)) {
                double anguloRelativo = Utils.normalRelativeAngle(Utilitario.anguloAbsoluto(p, alvo) - Utilitario.anguloAbsoluto(meuRobo, p));
                multiplicadorEvasao = 1.0 + ((1 - Math.abs(Math.sin(anguloRelativo))) + Math.abs(Math.cos(anguloRelativo))) / 2.0;
            }
            
            riscoMRM += riscoBase * multiplicadorRota * multiplicadorEvasao;
        }
        
        double votoTiros = 0;
        long tempoAtePonto = (long)(meuRobo.distance(p) / 8.0); 
        long tempoFuturo = getTime() + tempoAtePonto;
        
        Iterator<TiroInimigo> itTiros = tirosSuspeitos.iterator();
        while(itTiros.hasNext()) {
            TiroInimigo t = itTiros.next();
            double distTiroPercurso = (tempoFuturo - t.tempoDisparo) * t.velocidade;
            
            if(distTiroPercurso > 1500) { 
                itTiros.remove(); 
                continue; 
            }
            
            Point2D.Double posTiroPrevista = (Point2D.Double) Utilitario.projetar(t.origem, t.angulo, distTiroPercurso);
            
            if(posTiroPrevista.distanceSq(p) < 2500) { 
                votoTiros += 1050.5 / Math.max(1, posTiroPrevista.distanceSq(p));
            }
        }
        riscoMRM += votoTiros;
        
        if (!existeInimigoVivo) {
            riscoMRM += (1.1 + Math.abs(Utilitario.anguloAbsoluto(meuRobo, pontoAlvo) - getHeadingRadians()));
        }

        if (alvo != null && alvo.vivo && alvo.energia < 15 && meuRobo.energia > (alvo.energia * 2.5) && getOthers() <= 3) {
            riscoMRM -= (125.0 / Math.max(1, p.distance(alvo))); 
        }
        
        if (alvo != null && alvo.vivo && alvo.classificadoComoSurfer && existeInimigoVivo) {
            double distanciaParaSurfer = p.distance(alvo);
            if (distanciaParaSurfer > 200) {
                riscoMRM -= (850.0 * alvo.fatorSurf) / Math.max(1, distanciaParaSurfer); 
            } else {
                riscoMRM += (150.0 / Math.max(1, distanciaParaSurfer)); 
            }
        }
        
        if (alvo != null && alvo.vivo) {
            double direcaoRealInimigo = alvo.velocidade < 0 ? alvo.direcao + Math.PI : alvo.direcao;
            double anguloNossoPonto = Utilitario.anguloAbsoluto(meuRobo, p);
            double diferencaAngulo = Math.abs(Utils.normalRelativeAngle(direcaoRealInimigo - anguloNossoPonto));
            
            double votoImitacao = -18.5 * (1.0 - (diferencaAngulo / Math.PI)) * (Math.abs(alvo.velocidade) / 8.0);
            riscoMRM += votoImitacao;
        }
        
        double anguloParaPonto = Utilitario.anguloAbsoluto(meuRobo, p);
        double diferencaInercia = Math.abs(Utils.normalRelativeAngle(anguloParaPonto - getHeadingRadians()));
        
        double fatorCaos = Math.sin(getTime() / 85.5); 
        
        if (fatorCaos > 0.8 && diferencaInercia < 0.3) {
            riscoMRM += 35.0; 
        } else if (fatorCaos < -0.8 && diferencaInercia > Math.PI / 2) {
            riscoMRM += 35.0; 
        }
        
        ultimoRiscoSurfingAvaliado = riscoSurfing;
        ultimoRiscoMRMAvaliado = riscoMRM;
        
        riscoTotal = (riscoSurfing * confiancaSurfing) + (riscoMRM * confiancaMRM);
        
        return riscoTotal;
    }
}

