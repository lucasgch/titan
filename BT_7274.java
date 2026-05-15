package sample;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.*;

import robocode.*;
import robocode.util.Utils;

/**
 * BT-7274 (Núcleo Original + Movimentação MRM + Fuga + Trajetória + Caçador + Rank + Anti-Surf + Shadowing)
 * Estratégia Híbrida RECALIBRADA (Radar Agressivo/Fast-Learning): 
 * - Melee: Voting MRM + Perfilamento + Evasão Dinâmica + Fuga + Caçador + Imitação + Anti-Surf
 * - 1v1: Evasão com Wall-Smoothing + Mira GuessFactor (Aprendizado Acelerado)
 */
public class BT_7274 extends AdvancedRobot {
    
    // =========================================================
    // CONSTANTES GLOBAIS OTIMIZADAS
    // =========================================================
    static double POTENCIA_TIRO = 3;
    static final int QUANTIDADE_PONTOS_PREVISTOS = 150;
    static final double MARGEM_PAREDE = 22.5; // Ajustado de 28.5 para recuperar espaço de manobra
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
        
        // --- VARIÁVEIS DE PERFILAMENTO DE ESTRATÉGIA ---
        public double fatorAmeaca = 1.0; 
        public double energiaAnterior = 100;
        public double agressividade = 0.0;
        
        // --- RANK DE PRECISÃO (Valores positivos e negativos) ---
        public double saldoPrecisao = 0.0; 
        
        // --- DETECÇÃO DE WAVE SURFING ---
        public double fatorSurf = 0.0;
        public int reversoesLaterais = 0;
        public double ultimaVelocidadeLateral = 0.0;
        public boolean classificadoComoSurfer = false;
        
        // --- MULTIPLICADORES DE OTIMIZAÇÃO (Dados Treinados) ---
        public double pesoAprendizadoDinâmico = 1.0;
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
    // LÓGICA DE MOVIMENTO 1 VS 1 (EVASÃO + WALL SMOOTHING)
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
    // LÓGICA DE TIRO 1 VS 1 (GUESSFACTOR FAST-LEARNING)
    // =========================================================
    static class Onda extends Condition {
        static Point2D posicaoAlvo;
        double potenciaTiro;
        Point2D posicaoCanhao;
        double angulo;
        double direcaoLateral;
        
        private static final double DISTANCIA_MAXIMA = 900;
        private static final int INDICES_DISTANCIA = 5;
        private static final int INDICES_VELOCIDADE = 5;
        // OTIMIZAÇÃO DE RADAR: Reduzido de 31 para 15. Aprende o dobro mais rápido!
        private static final int BINS = 15; 
        private static final int BIN_CENTRAL = (BINS - 1) / 2;
        private static final double ANGULO_ESCAPE_MAXIMO = 0.7;
        private static final double LARGURA_BIN = ANGULO_ESCAPE_MAXIMO / (double) BIN_CENTRAL; 
        
        private static final int[][][][] buffersEstatisticos = new int[INDICES_DISTANCIA][INDICES_VELOCIDADE][INDICES_VELOCIDADE][BINS];
        private int[] buffer;
        private double distanciaPercorrida;
        private final AdvancedRobot robô;
        public double pesoImpacto = 5.0; // Radar hiper-focado: sucessos pesam muito mais na memória
        
        Onda(AdvancedRobot _robô) {
            this.robô = _robô;
        }
        
        public boolean test() {
            avancar();
            if (chegou()) {
                // Multiplicador 10 para super-valorizar a taxa de acerto no buffer
                buffer[binAtual()] += (int)Math.round(10 * pesoImpacto); 
                robô.removeCustomEvent(this);
            }
            return false;
        }
        
        double offsetAnguloMaisVisitado() {
            return (direcaoLateral * LARGURA_BIN) * (binMaisVisitado() - BIN_CENTRAL);
        }
        
        void definirSegmentacoes(double distancia, double velocidade, double ultimaVelocidade) {
            int indiceDistancia = (int) (distancia / (DISTANCIA_MAXIMA / INDICES_DISTANCIA));
            int indiceVelocidade = (int) Math.abs(velocidade / 2);
            int indiceUltimaVelocidade = (int) Math.abs(ultimaVelocidade / 2);
            buffer = buffersEstatisticos[indiceDistancia][indiceVelocidade][indiceUltimaVelocidade];
        }
        
        private void avancar() {
            distanciaPercorrida += Rules.getBulletSpeed(potenciaTiro);
        }
        
        private boolean chegou() {
            return distanciaPercorrida > posicaoCanhao.distance(posicaoAlvo) - MARGEM_PAREDE;
        }
        
        private int binAtual() {
            int bin = (int) Math.round(((Utils.normalRelativeAngle(
                Utilitario.anguloAbsoluto(posicaoCanhao, posicaoAlvo) - angulo)) /
                (direcaoLateral * LARGURA_BIN)) + BIN_CENTRAL);
            return (int) Utilitario.limitar(bin, 0, BINS - 1);
        }
        
        private int binMaisVisitado() {
            int maisVisitado = BIN_CENTRAL;
            for (int i = 0; i < BINS; i++) {
                if (buffer[i] > buffer[maisVisitado]) maisVisitado = i;
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
            while (true) {
                turnRadarRightRadians(Double.POSITIVE_INFINITY);
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
            
            // --- CÁLCULO PRECISO DE DETECÇÃO DE SURF ---
            double velLateral = inimigo.velocidade * Math.sin(inimigo.direcao - (getHeadingRadians() + inimigo.anguloAbsolutoRadianos));
            if (Math.abs(velLateral) > 0.1 && Math.abs(inimigo.ultimaVelocidadeLateral) > 0.1) {
                if (Utilitario.sinal(velLateral) != Utilitario.sinal(inimigo.ultimaVelocidadeLateral)) {
                    inimigo.reversoesLaterais++;
                }
            }
            inimigo.ultimaVelocidadeLateral = velLateral;
            
            // Avalia: Ele desvia dos tiros? Fica perpendicular? Muda de direção muito?
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
            // -------------------------------------------
            
            inimigo.pontuacaoDisparo = inimigo.energia < 25 ? (inimigo.energia < 5 ?
                    (inimigo.energia == 0 ? Double.MIN_VALUE : inimigo.distance(meuRobo) * 0.1) :
                    inimigo.distance(meuRobo) * 0.75) : inimigo.distance(meuRobo);
                    
            // --- RANK DE PRECISÃO (Preferência aos dados mais precisos) ---
            inimigo.pontuacaoDisparo -= (inimigo.saldoPrecisao * 25.85);
            // --------------------------------------------------------------
                    
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
            
            if (inimigo.velocidade != 0) {
                direcaoLateral = Utilitario.sinal(inimigo.velocidade * Math.sin(e.getHeadingRadians() - inimigo.anguloAbsolutoRadianos));
            }
                
            Onda onda = new Onda(this);
            onda.posicaoCanhao = new Point2D.Double(getX(), getY());
            Onda.posicaoAlvo = Utilitario.projetar(onda.posicaoCanhao, inimigo.anguloAbsolutoRadianos, inimigo.distancia);
            onda.direcaoLateral = direcaoLateral;
            onda.definirSegmentacoes(inimigo.distancia, inimigo.velocidade, velocidadeInimigoAnterior);
            
            // Recompensa o aprendizado dinâmico se a precisão do alvo estiver alta
            if(alvo != null && alvo.saldoPrecisao > 5) {
                onda.pesoImpacto = 12.0; // Aumento expressivo para focar no padrão letal
            }
            
            velocidadeInimigoAnterior = inimigo.velocidade;
            onda.angulo = inimigo.anguloAbsolutoRadianos;
            
            setTurnGunRightRadians(Utils.normalRelativeAngle(
                    inimigo.anguloAbsolutoRadianos - getGunHeadingRadians() + onda.offsetAnguloMaisVisitado()));
                    
            POTENCIA_TIRO = Math.min(3, Math.min(this.getEnergy(), e.getEnergy()) / (double) 4);
            onda.potenciaTiro = POTENCIA_TIRO;
            
            if (getEnergy() < 2 && e.getDistance() < 500) {
                onda.potenciaTiro = 0.1;
            } else if (e.getDistance() >= 500) {
                onda.potenciaTiro = 1.1;
            }
            
            // --- POSTURA AGRESSIVA EM 1V1 (Com moderação de bateria) ---
            if (inimigo.distancia < 150) {
                onda.potenciaTiro = Math.min(3.0, getEnergy() / 12); // Alterado de /5 para /12 para não esgotar a energia
            }
            POTENCIA_TIRO = onda.potenciaTiro;
            // ------------------------------------------------------------
                
            setFire(onda.potenciaTiro);
            
            if (getEnergy() >= onda.potenciaTiro) {
                addCustomEvent(onda);
            }
                
            movimento1VS1.onScannedRobot(e);
            setTurnRadarRightRadians(Utils.normalRelativeAngle(inimigo.anguloAbsolutoRadianos - getRadarHeadingRadians()) * 2);
        }
    }

    public void onHitByBullet(HitByBulletEvent e) {
        Robo inimigo = listaInimigos.get(e.getName());
        if (inimigo != null) {
            inimigo.agressividade += 0.5;
            inimigo.fatorAmeaca *= 1.1; // Suavizado para evitar fugas em pânico
        }
    }
    
    // --- COLETA DE DADOS DE PRECISÃO (Acelerando convergência estatística) ---
    public void onBulletHit(BulletHitEvent e) {
        Robo inimigo = listaInimigos.get(e.getName());
        if (inimigo != null) {
            inimigo.saldoPrecisao += 15.0; // Triplicado! O radar confia cegamente no que funciona
        }
    }

    public void onBulletMissed(BulletMissedEvent e) {
        if (alvo != null && alvo.vivo) {
            alvo.saldoPrecisao -= 5.0; // Erros são punidos mais rápido na memória
        }
    }
    
    public void onBulletHitBullet(BulletHitBulletEvent e) {
        if (alvo != null && alvo.vivo) {
            alvo.saldoPrecisao -= 1.0; 
        }
    }
    // -------------------------------------------------------------------------

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
            
            // --- POSTURA AGRESSIVA MELEE ---
            if (distancia < 150) {
                potencia = Math.min(3.0, meuRobo.energia / 12); // Ajuste conservador de energia
            }
            // -----------------------------------------------------------
            
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
    // LÓGICA DE MOVIMENTO MINIMUM RISK (NERF DE COLISÕES)
    // =========================================================
    public void movimento() {
        if (pontoAlvo.distance(meuRobo) < 15 || tempoInativo > 25) {
            tempoInativo = 0;
            atualizarListaPosicoes(QUANTIDADE_PONTOS_PREVISTOS);
            
            Point2D.Double pontoMenorRisco = null;
            double menorRisco = Double.MAX_VALUE;
            
            for (Point2D.Double p : posicoesPossiveis) {
                double riscoAtual = avaliarPonto(p);
                if (riscoAtual <= menorRisco || pontoMenorRisco == null) {
                    menorRisco = riscoAtual;
                    pontoMenorRisco = p;
                }
            }
            pontoAlvo = pontoMenorRisco;
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
        
        // VOTO 1: Estabilidade de movimentação
        double votoEstabilidade = Utilitario.aleatorioEntre(1.15, 2.42) / p.distanceSq(meuRobo);
        riscoTotal += votoEstabilidade;
        
        // VOTO 2: Controle de Arena
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
                            
        riscoTotal += votoCentro + votoCantos;
        
        // VOTO 3: Fuga Baseada no Perfilamento
        boolean existeInimigoVivo = false;
        Iterator<Robo> iteradorInimigos = listaInimigos.values().iterator();
        
        while (iteradorInimigos.hasNext()) {
            Robo inimigo = iteradorInimigos.next();
            if (!inimigo.vivo) continue;
            existeInimigoVivo = true;
            
            double distanciaSqInimigo = p.distanceSq(inimigo);
            double riscoBase = (1 / Math.max(1, distanciaSqInimigo));
            
            if (modoFuga) riscoBase *= 3.14; 
            
            riscoBase *= inimigo.fatorAmeaca;
            
            double alinhamentoPonto = Math.abs(Math.cos(Utilitario.anguloAbsoluto(meuRobo, p) - Utilitario.anguloAbsoluto(inimigo, p)));
            double multiplicadorRota = (1 + alinhamentoPonto);

            double multiplicadorEvasao = 1.0;
            if (alvo != null && alvo.vivo && inimigo.nome.equals(alvo.nome)) {
                double anguloRelativo = Utils.normalRelativeAngle(Utilitario.anguloAbsoluto(p, alvo) - Utilitario.anguloAbsoluto(meuRobo, p));
                multiplicadorEvasao = 1.0 + ((1 - Math.abs(Math.sin(anguloRelativo))) + Math.abs(Math.cos(anguloRelativo))) / 2.0;
            }
            
            riscoTotal += riscoBase * multiplicadorRota * multiplicadorEvasao;
        }
        
        // VOTO 4: Risco de Trajetória de Tiro
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
        riscoTotal += votoTiros;
        
        // VOTO 5: Penalidade Curva Brusca
        if (!existeInimigoVivo) {
            riscoTotal += (1.1 + Math.abs(Utilitario.anguloAbsoluto(meuRobo, pontoAlvo) - getHeadingRadians()));
        }

        // --- VOTO 6: MODO CAÇADOR (NERFADO PARA EVITAR COLISÕES) ---
        if (alvo != null && alvo.vivo && alvo.energia < 15 && meuRobo.energia > (alvo.energia * 2.5) && getOthers() <= 3) {
            riscoTotal -= (125.0 / Math.max(1, p.distance(alvo))); // Reduzido de 1085.2
        }
        // ------------------------------------------------
        
        // --- VOTO 7: TÁTICA ANTI-SURF OTIMIZADA ---
        if (alvo != null && alvo.vivo && alvo.classificadoComoSurfer && existeInimigoVivo) {
            double distanciaParaSurfer = p.distance(alvo);
            if (distanciaParaSurfer > 200) {
                riscoTotal -= (850.0 * alvo.fatorSurf) / Math.max(1, distanciaParaSurfer); // Reduzido de 1618.3
            } else {
                riscoTotal += (150.0 / Math.max(1, distanciaParaSurfer)); // Reduzido de 521.4
            }
        }
        // --------------------------------
        
        // =========================================================================
        // NERF TÁTICO: SHADOWING & CAOS AGORA SÃO APENAS MICRO-AJUSTES
        // =========================================================================
        
        // --- VOTO 8: IMITAÇÃO DE MOVIMENTO (SHADOWING) ---
        if (alvo != null && alvo.vivo) {
            double direcaoRealInimigo = alvo.velocidade < 0 ? alvo.direcao + Math.PI : alvo.direcao;
            double anguloNossoPonto = Utilitario.anguloAbsoluto(meuRobo, p);
            double diferencaAngulo = Math.abs(Utils.normalRelativeAngle(direcaoRealInimigo - anguloNossoPonto));
            
            // Peso de imitação reduzido drasticamente para -18.5 (antes -342.75). Evita que ele vá para cima do tiro.
            double votoImitacao = -18.5 * (1.0 - (diferencaAngulo / Math.PI)) * (Math.abs(alvo.velocidade) / 8.0);
            riscoTotal += votoImitacao;
        }
        
        // --- MELHORIA DE SURFING: QUEBRA DE PADRÃO E INÉRCIA ---
        double anguloParaPonto = Utilitario.anguloAbsoluto(meuRobo, p);
        double diferencaInercia = Math.abs(Utils.normalRelativeAngle(anguloParaPonto - getHeadingRadians()));
        
        // Onda caótica bem mais longa, não vai ficar parando a toda hora
        double fatorCaos = Math.sin(getTime() / 85.5); // Aumentado o divisor de 11.83 para 85.5
        
        if (fatorCaos > 0.8 && diferencaInercia < 0.3) {
            riscoTotal += 35.0; // Penalidade quase simbólica (antes 1642.5)
        } else if (fatorCaos < -0.8 && diferencaInercia > Math.PI / 2) {
            riscoTotal += 35.0; 
        }
        // =========================================================================
        
        return riscoTotal;
    }
}

