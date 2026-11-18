package com.irythill.rwg3.preview;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.LayoutFocusTraversalPolicy;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import com.irythill.rwg3.procgen.GenerationStep;
import com.irythill.rwg3.procgen.continent.ContinentGenerator.StepDefinition;
import com.irythill.rwg3.procgen.world.WorldGenerator;

public final class PreviewWindow extends JFrame {

    private static final long serialVersionUID = 1L;
    private GenerationStep[] previewStages = new GenerationStep[0];
    private GenerationStep[] registeredStages = new GenerationStep[0];

    private final PreviewCanvas canvas = new PreviewCanvas();
    private final JLayeredPane layers = new JLayeredPane();
    private final JPanel seedPanel = floatingPanel();
    private final JTextField seedField = new JTextField("123", 13);
    private final JButton randomizeButton = new JButton(new RandomizeIcon());
    private final JLabel stageLabel = new JLabel("Generating…", SwingConstants.CENTER);
    private final JPanel timingLabel = new JPanel();
    private final JPanel hexTimingLabel = new JPanel();
    private final JPanel columnTimingLabel = new JPanel();
    private final Map<GenerationStep, Double> hexTimings = new LinkedHashMap<>();
    private final Map<GenerationStep, Double> columnTimings = new LinkedHashMap<>();
    private final SecureRandom seedSource = new SecureRandom();
    private final ThreadPoolExecutor continentLoader = new ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(),
        runnable -> daemon(runnable, "rwg-continent-loader"));
    private final AtomicLong continentLoadTickets = new AtomicLong();
    private int stageIndex;
    private transient WorldGenerator engine;
    private transient WorldGenerator composer;

    public PreviewWindow() {
        super("RWG3 — World Planner Preview");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(true);
        setContentPane(createLayeredContent());
        setFocusTraversalPolicy(new LayoutFocusTraversalPolicy() {

            private static final long serialVersionUID = 1L;

            @Override
            public Component getDefaultComponent(Container container) {
                return canvas;
            }
        });
        installActions();
        pack();
        setSize(new Dimension(900, 900));
        setMinimumSize(new Dimension(480, 400));
        setLocationRelativeTo(null);
        canvas.setViewportListener(this::requestVisibleContinents);
        canvas.setTimingListener(values -> {
            if (!values.isEmpty() && values.keySet()
                .iterator()
                .next() instanceof StepDefinition) {
                hexTimings.clear();
                hexTimings.putAll(values);
            } else {
                columnTimings.clear();
                columnTimings.putAll(values);
            }
            updateTimingLabel();
        });
        generate(123L);
    }

    private JComponent createLayeredContent() {
        layers.setPreferredSize(new Dimension(900, 900));
        layers.add(canvas, JLayeredPane.DEFAULT_LAYER);

        seedPanel.add(new JLabel("Seed"));
        seedField.setMargin(new Insets(4, 6, 4, 6));
        seedField.setFocusTraversalKeysEnabled(false);
        seedPanel.add(seedField);
        randomizeButton.setPreferredSize(new Dimension(30, 27));
        randomizeButton.setFocusable(false);
        randomizeButton.setToolTipText("<html><u>R</u>andomize seed</html>");
        seedPanel.add(randomizeButton);
        layers.add(seedPanel, JLayeredPane.PALETTE_LAYER);

        timingLabel.setOpaque(false);
        timingLabel.setLayout(new javax.swing.BoxLayout(timingLabel, javax.swing.BoxLayout.Y_AXIS));
        updateTimingLabel();
        layers.add(timingLabel, JLayeredPane.PALETTE_LAYER);

        stageLabel.setForeground(Color.WHITE);
        stageLabel.setFont(
            stageLabel.getFont()
                .deriveFont(Font.BOLD, 13.0f));
        stageLabel.setOpaque(true);
        stageLabel.setBackground(new Color(0, 0, 0, 175));
        stageLabel.setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
        layers.add(stageLabel, JLayeredPane.PALETTE_LAYER);
        layers.addComponentListener(new ComponentAdapter() {

            @Override
            public void componentResized(ComponentEvent event) {
                layoutOverlays();
            }
        });
        layoutOverlays();
        return layers;
    }

    private void layoutOverlays() {
        int width = layers.getWidth();
        int height = layers.getHeight();
        canvas.setBounds(0, 0, width, height);

        Dimension seedSize = seedPanel.getPreferredSize();
        seedPanel.setBounds(18, 18, seedSize.width, seedSize.height);
        Dimension timingSize = timingLabel.getPreferredSize();
        timingLabel.setBounds(18, 26 + seedSize.height, timingSize.width, timingSize.height);

        Dimension labelSize = stageLabel.getPreferredSize();
        stageLabel.setBounds(
            Math.max(8, (width - labelSize.width) / 2),
            Math.max(8, height - labelSize.height - 18),
            Math.min(labelSize.width, Math.max(1, width - 16)),
            labelSize.height);
    }

    private static JPanel floatingPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.setBackground(new Color(245, 245, 245, 235));
        panel.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 70, 70)),
                BorderFactory.createEmptyBorder(5, 7, 5, 7)));
        return panel;
    }

    private void installActions() {
        seedField.addActionListener(event -> applySeed());
        randomizeButton.addActionListener(event -> randomizeSeed());
        JComponent root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "nextStage");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "nextStage");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "nextStage");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK), "previousStage");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "previousStage");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "previousStage");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "randomSeed");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), "resetView");
        root.getActionMap()
            .put("nextStage", new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent event) {
                    moveStage(1);
                }
            });
        root.getActionMap()
            .put("previousStage", new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent event) {
                    moveStage(-1);
                }
            });
        root.getActionMap()
            .put("randomSeed", new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent event) {
                    randomizeSeed();
                }
            });
        root.getActionMap()
            .put("resetView", new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent event) {
                    canvas.resetView();
                }
            });
    }

    private void applySeed() {
        try {
            long seed = Long.parseLong(
                seedField.getText()
                    .trim());
            seedField.setBackground(Color.WHITE);
            generate(seed);
        } catch (NumberFormatException exception) {
            seedField.setBackground(new Color(255, 210, 210));
            seedField.setToolTipText("Enter a signed 64-bit integer");
        }
    }

    private void randomizeSeed() {
        long seed = seedSource.nextLong();
        seedField.setText(Long.toString(seed));
        seedField.setBackground(Color.WHITE);
        generate(seed);
    }

    private void generate(long seed) {
        engine = WorldGenerator.forPreview(seed);
        previewStages = engine.steps()
            .toArray(new GenerationStep[0]);
        registeredStages = engine.registeredSteps()
            .toArray(new GenerationStep[0]);
        hexTimings.clear();
        columnTimings.clear();
        updateTimingLabel();
        composer = engine;
        canvas.setWorld(composer, seed);
        canvas.setStage(previewStages[stageIndex]);
        requestVisibleContinents();
        updateStageLabel();
    }

    private void updateTimingLabel() {
        populateTimingBox(hexTimingLabel, true, hexTimings);
        populateTimingBox(columnTimingLabel, false, columnTimings);
        int width = Math.max(hexTimingLabel.getPreferredSize().width, columnTimingLabel.getPreferredSize().width);
        hexTimingLabel.setPreferredSize(new Dimension(width, hexTimingLabel.getPreferredSize().height));
        columnTimingLabel.setPreferredSize(new Dimension(width, columnTimingLabel.getPreferredSize().height));
        timingLabel.removeAll();
        timingLabel.add(hexTimingLabel);
        timingLabel.add(javax.swing.Box.createVerticalStrut(6));
        timingLabel.add(columnTimingLabel);
        timingLabel.revalidate();
        timingLabel.repaint();
        layoutOverlays();
    }

    private void populateTimingBox(JPanel box, boolean hexes, Map<GenerationStep, Double> values) {
        // Recompute from the populated rows; the initial empty-state width must not remain pinned.
        box.setPreferredSize(null);
        box.removeAll();
        box.setLayout(new javax.swing.BoxLayout(box, javax.swing.BoxLayout.Y_AXIS));
        box.setOpaque(true);
        box.setBackground(new Color(245, 245, 245, 235));
        box.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                    hexes ? "Hex generation - done once, then cached"
                        : "Column generation - used by worldgen, reads cached hexmap"),
                BorderFactory.createEmptyBorder(1, 6, 5, 6)));
        Dimension numericSize = new JLabel("+999.9 µs/sample").getPreferredSize();
        numericSize = new Dimension(numericSize.width + 6, numericSize.height);
        double previous = 0.0;
        double total = 0.0;
        for (GenerationStep stage : registeredStages) {
            if (hexes != (stage instanceof StepDefinition)) continue;
            double cumulative = values.getOrDefault(stage, 0.0);
            box.add(
                timingRow(
                    stage.label(),
                    formatTiming(cumulative, false, hexes),
                    formatTiming(cumulative - previous, true, hexes),
                    numericSize));
            previous = cumulative;
            total = cumulative;
        }
        JSeparator separator = new JSeparator();
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        box.add(javax.swing.Box.createVerticalStrut(3));
        box.add(separator);
        box.add(javax.swing.Box.createVerticalStrut(3));
        box.add(timingRow("Total", formatTiming(total, false, hexes), "", numericSize));
    }

    private static JPanel timingRow(String name, String total, String delta, Dimension numericSize) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.add(new JLabel(name + " "), BorderLayout.CENTER);
        JPanel numbers = new JPanel(new GridLayout(1, 2));
        numbers.setOpaque(false);
        numbers.add(timingValue(total, numericSize));
        numbers.add(timingValue(delta, numericSize));
        row.add(numbers, BorderLayout.EAST);
        return row;
    }

    private static JLabel timingValue(String text, Dimension size) {
        JLabel value = new JLabel(text);
        value.setPreferredSize(size);
        value.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(150, 150, 150)),
                BorderFactory.createEmptyBorder(0, 5, 0, 0)));
        return value;
    }

    private static String formatTiming(double micros, boolean signed, boolean hexes) {
        double absolute = Math.abs(micros);
        double value;
        String unit;
        if (absolute >= 1_000.0) {
            value = micros / 1_000.0;
            unit = "ms";
        } else if (absolute < 1.0) {
            value = micros * 1_000.0;
            unit = "ns";
        } else {
            value = micros;
            unit = "µs";
        }
        return String.format(Locale.ROOT, signed ? "%+.1f %s/%s" : "%.1f %s/%s", value, unit, hexes ? "hex" : "sample");
    }

    private void moveStage(int amount) {
        stageIndex = Math.floorMod(stageIndex + amount, previewStages.length);
        showStage();
    }

    private void showStage() {
        canvas.setStage(previewStages[stageIndex]);
        updateTimingLabel();
        updateStageLabel();
        layoutOverlays();
    }

    private void requestVisibleContinents() {
        if (composer == null || canvas.getWidth() <= 0) return;
        WorldGenerator requestedComposer = composer;
        Rectangle2D bounds = canvas.worldBounds();
        double scale = WorldGenerator.BLOCKS_PER_WORLD_UNIT;
        Rectangle2D blockBounds = new Rectangle2D.Double(
            bounds.getMinX() * scale,
            -bounds.getMaxY() * scale,
            bounds.getWidth() * scale,
            bounds.getHeight() * scale);
        long ticket = continentLoadTickets.incrementAndGet();
        // Only the newest queued viewport matters; a running generation is allowed to finish and populate its cache.
        continentLoader.getQueue()
            .clear();
        continentLoader.execute(() -> {
            requestedComposer.prepare(blockBounds);
            SwingUtilities.invokeLater(() -> {
                if (composer != requestedComposer || continentLoadTickets.get() != ticket) return;
                canvas.planCompleted();
                updateStageLabel();
            });
        });
    }

    private void updateStageLabel() {
        if (previewStages.length == 0) return;
        GenerationStep stage = previewStages[stageIndex];
        stageLabel.setText((stageIndex + 1) + " / " + previewStages.length + " — " + stage.label());
        layoutOverlays();
    }

    private static final class RandomizeIcon implements Icon {

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(component.isEnabled() ? new Color(40, 50, 65) : Color.GRAY);
            g.setStroke(new BasicStroke(1.7f));
            g.drawArc(x + 2, y + 2, 11, 11, 35, 245);
            g.fillPolygon(new int[] { x + 13, x + 13, x + 9 }, new int[] { y + 1, y + 7, y + 4 }, 3);
            g.dispose();
        }
    }

    @Override
    public void dispose() {
        continentLoadTickets.incrementAndGet();
        continentLoader.shutdownNow();
        canvas.close();
        super.dispose();
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
