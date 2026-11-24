package com.irythill.rwg3.preview;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import com.irythill.rwg3.world.World;
import com.irythill.rwg3.world.WorldGenerator;
import com.irythill.rwg3.world.WorldGenerator.StepDefinition;

/** Standalone controls around the fixed-world preview canvas. */
public final class PreviewWindow extends JFrame {

    private final PreviewCanvas canvas = new PreviewCanvas();
    private final JTextField seedField = new JTextField("123", 18);
    private final JLabel stageLabel = new JLabel("Generating world…");
    private final JLabel timingLabel = new JLabel(" ");
    private final JButton previous = new JButton("Previous");
    private final JCheckBox treeIcons = new JCheckBox("Tree icons", true);
    private final JButton next = new JButton("Next");
    private final ExecutorService generator = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rwg-world-generator");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong tickets = new AtomicLong();
    private final Random seedSource = new Random();
    private final KeyEventDispatcher shortcutDispatcher = this::dispatchShortcut;
    private List<StepDefinition> stages;
    private World world;
    private int stageIndex;

    public PreviewWindow() {
        super("RWG3 World Preview");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(800, 650));
        setSize(1200, 850);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        add(canvas, BorderLayout.CENTER);
        add(controls(), BorderLayout.NORTH);
        installKeys(getRootPane());
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(shortcutDispatcher);
        generate(123L);
    }

    private JPanel controls() {
        JPanel panel = new JPanel(new BorderLayout(12, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));
        JPanel seed = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        seed.add(new JLabel("Seed"));
        seed.add(seedField);
        JButton apply = new JButton("Generate");
        JButton random = new JButton("Random");
        apply.addActionListener(event -> applySeed());
        random.addActionListener(event -> randomizeSeed());
        seedField.addActionListener(event -> applySeed());
        seed.add(apply);
        seed.add(random);
        panel.add(seed, BorderLayout.WEST);

        JPanel status = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        stageLabel.setFont(
            stageLabel.getFont()
                .deriveFont(Font.BOLD));
        status.add(stageLabel);
        status.add(timingLabel);
        treeIcons.setToolTipText("Show trees for supported biomes");
        treeIcons.addActionListener(event -> canvas.setShowTreeIcons(treeIcons.isSelected()));
        status.add(treeIcons);
        panel.add(status, BorderLayout.SOUTH);

        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        previous.addActionListener(event -> moveStage(-1));
        next.addActionListener(event -> moveStage(1));
        JButton home = new JButton("Fit world");
        home.addActionListener(event -> canvas.resetView());
        navigation.add(previous);
        navigation.add(next);
        navigation.add(home);
        panel.add(navigation, BorderLayout.EAST);
        setNavigationEnabled(false);
        return panel;
    }

    private void installKeys(JRootPane root) {
        root.getInputMap(JRootPane.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "next");
        root.getInputMap(JRootPane.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK), "previous");
        root.getInputMap(JRootPane.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "next");
        root.getInputMap(JRootPane.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "next");
        root.getInputMap(JRootPane.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "previous");
        root.getInputMap(JRootPane.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "previous");
        root.getInputMap(JRootPane.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), "home");
        root.getInputMap(JRootPane.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "random");
        root.getActionMap()
            .put("next", new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent event) {
                    moveStage(1);
                }
            });
        root.getActionMap()
            .put("previous", new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent event) {
                    moveStage(-1);
                }
            });
        root.getActionMap()
            .put("home", new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent event) {
                    canvas.resetView();
                }
            });
        root.getActionMap()
            .put("random", new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent event) {
                    randomizeSeed();
                }
            });
    }

    private boolean dispatchShortcut(KeyEvent event) {
        Object source = event.getSource();
        if (!(source instanceof Component) || SwingUtilities.getWindowAncestor((Component) source) != this)
            return false;
        int direction;
        switch (event.getKeyCode()) {
            case KeyEvent.VK_TAB:
                direction = event.isShiftDown() ? -1 : 1;
                break;
            case KeyEvent.VK_UP:
            case KeyEvent.VK_RIGHT:
                direction = 1;
                break;
            case KeyEvent.VK_DOWN:
            case KeyEvent.VK_LEFT:
                direction = -1;
                break;
            case KeyEvent.VK_HOME:
                if (event.getID() == KeyEvent.KEY_PRESSED) canvas.resetView();
                return true;
            case KeyEvent.VK_R:
                if (event.getID() == KeyEvent.KEY_PRESSED) randomizeSeed();
                return true;
            default:
                return false;
        }
        if (event.getID() == KeyEvent.KEY_PRESSED) moveStage(direction);
        return true;
    }

    private void randomizeSeed() {
        long value = seedSource.nextLong();
        seedField.setText(Long.toString(value));
        generate(value);
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
        }
    }

    private void generate(long seed) {
        long ticket = tickets.incrementAndGet();
        setNavigationEnabled(false);
        stageLabel.setText("Generating complete 30,000 × 30,000 world…");
        timingLabel.setText(" ");
        generator.execute(() -> {
            WorldGenerator engine = WorldGenerator.traced();
            World generated = engine.generate(seed);
            SwingUtilities.invokeLater(() -> {
                if (ticket != tickets.get()) return;
                world = generated;
                stages = engine.steps();
                stageIndex = Math.min(stageIndex, stages.size() - 1);
                canvas.setWorld(world, stages.get(stageIndex));
                setNavigationEnabled(true);
                updateStage();
            });
        });
    }

    private void moveStage(int amount) {
        if (stages == null || stages.isEmpty()) return;
        stageIndex = Math.floorMod(stageIndex + amount, stages.size());
        canvas.setStage(stages.get(stageIndex));
        updateStage();
    }

    private void updateStage() {
        StepDefinition stage = stages.get(stageIndex);
        treeIcons.setEnabled(PreviewCanvas.hasTreeIcons(stage));
        stageLabel.setText((stageIndex + 1) + " / " + stages.size() + " — " + stage.label);
        long cumulative = 0L;
        for (int index = 0; index <= stageIndex; index++) cumulative += world.timingsNanos()
            .getOrDefault(index, 0L);
        long stepNanos = world.timingsNanos()
            .getOrDefault(stageIndex, 0L);
        double nanosToMicrosPerHexDivisor = 1_000.0 * world.grid.coordinates.size();
        timingLabel.setText(
            String.format(
                Locale.ROOT,
                "+%.1f µs/hex, cumulative %.1f µs/hex",
                stepNanos / nanosToMicrosPerHexDivisor,
                cumulative / nanosToMicrosPerHexDivisor));
    }

    private void setNavigationEnabled(boolean enabled) {
        if (!enabled) treeIcons.setEnabled(false);
        previous.setEnabled(enabled);
        next.setEnabled(enabled);
    }

    @Override
    public void dispose() {
        tickets.incrementAndGet();
        generator.shutdownNow();
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .removeKeyEventDispatcher(shortcutDispatcher);
        super.dispose();
    }
}
