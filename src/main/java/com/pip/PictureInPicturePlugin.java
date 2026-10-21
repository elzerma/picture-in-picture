package com.pip;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.FontManager;

import java.awt.*;
import java.awt.Point;
import java.awt.image.*;
import java.awt.event.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.swing.*;

import static com.pip.PictureInPictureConfig.clickAction.*;


@Slf4j
@PluginDescriptor(
	name = "Picture In Picture",
	description = "Displays picture in picture mode when RuneLite is not in focus",
	tags = {"pip", "picture", "display", "afk"}
)
public class PictureInPicturePlugin extends Plugin
{
	private static boolean focused = true;
	private static boolean pipUp = false;
	// after a click-to-focus, don't reopen the PIP until focus arrives or this time passes
	private static final long FOCUS_GRACE_MS = 2000;
	private volatile long suppressPipUntil = 0;
	private JFrame pipFrame = null;
	private JLabel lbl = null;
	private pipBar leftBar, rightBar;
	private Skill leftSkill, rightSkill;
	private Point pipPoint = new Point(0, 0);
	private boolean hovered = false;
	private boolean perPixelOpacity = false;
	private boolean windowOpacity = false;

	private int clientTick = 0;
	private int pipWidth, pipHeight;
	private double pipScale;
	private int maxHealth, currentHealth, maxPrayer, currentPrayer;
	private Color[] healthColor;

	private static final Color PRAYER_COLOR = new Color(32, 160, 160);
	private static final Color PRAYER_BG_COLOR = new Color(10, 50, 50);
	private static final Color[] PRAYER = {PRAYER_COLOR, PRAYER_BG_COLOR};

	private static final Color HEALTH_COLOR = new Color(160, 32, 0);
	private static final Color HEALTH_BG_COLOR = new Color(50, 10, 0);
	private static final Color[] HEALTH = {HEALTH_COLOR, HEALTH_BG_COLOR};

	private static final Color POISONED_COLOR = new Color(0, 160, 0);
	private static final Color POISONED_BG_COLOR = new Color(0, 50, 0);
	private static final Color[] POISONED = {POISONED_COLOR, POISONED_BG_COLOR};

	private static final Color VENOMED_COLOR = new Color(0, 90, 0);
	private static final Color VENOMED_BG_COLOR = new Color(0, 25, 0);
	private static final Color[] VENOMED = {VENOMED_COLOR, VENOMED_BG_COLOR};

	private static final Color DISEASE_COLOR = new Color(200, 160, 64);
	private static final Color DISEASE_BG_COLOR = new Color(63, 50, 20);
	private static final Color[] DISEASE = {DISEASE_COLOR, DISEASE_BG_COLOR};

	class MoveMouseListener implements MouseListener, MouseMotionListener
	{
		JFrame target;
		Point startDrag;
		Point startLocation;

		public MoveMouseListener(JFrame target)
		{
			this.target = target;
		}

		public JFrame getFrame(Container target)
		{
			if (target instanceof JFrame)
			{
				return (JFrame) target;
			}
			return getFrame(target.getParent());
		}

		Point getScreenLocation(MouseEvent e)
		{
			Point cursor = e.getPoint();
			Point target_location = this.target.getLocationOnScreen();
			return new Point((int) (target_location.getX() + cursor.getX()), (int) (target_location.getY() + cursor.getY()));
		}

		public void mouseClicked(MouseEvent e)
		{
		}

		public void mouseEntered(MouseEvent e)
		{
			setHovered(true);
		}

		public void mouseExited(MouseEvent e)
		{
			// exit also fires when moving onto a child component, so only un-hover when leaving the window
			if (!this.target.getBounds().contains(e.getLocationOnScreen()))
			{
				setHovered(false);
			}
		}

		public void mousePressed(MouseEvent e)
		{
			PictureInPictureConfig.clickAction action = e.isShiftDown() ? config.shiftClickAction() : config.clickAction();
			if (action == DRAG_MODE)
			{
				this.startDrag = this.getScreenLocation(e);
				this.startLocation = this.target.getLocation();
			}
			else if (action == REQUEST_FOCUS || action == FORCE_FOCUS)
			{
				focusClient();
			}
		}

		public void mouseReleased(MouseEvent e)
		{
			int newX, newY;
			int offset = getOffset();
			Rectangle effectiveScreenArea = getEffectiveScreenArea();

			if (config.quadrantID().getId() == 1)
			{
				newX = effectiveScreenArea.width - pipWidth - pipPoint.x - offset;
				newY = pipPoint.y;
			}
			else if (config.quadrantID().getId() == 2)
			{
				newX = pipPoint.x;
				newY = pipPoint.y;
			}
			else if (config.quadrantID().getId() == 3)
			{
				newX = pipPoint.x;
				newY = effectiveScreenArea.height - pipHeight - pipPoint.y - 2 * config.borderWidth();
			}
			else
			{
				newX = effectiveScreenArea.width - pipWidth - pipPoint.x - offset;
				newY = effectiveScreenArea.height - pipHeight - pipPoint.y - 2 * config.borderWidth();
			}

			if (config.preserveShiftDrag())
			{
				configManager.setConfiguration("pip", "paddingX", newX);
				configManager.setConfiguration("pip", "paddingY", newY);
			}
		}

		public void mouseDragged(MouseEvent e)
		{
			// A drag is only valid when mousePressed entered drag mode and recorded a start point.
			// Otherwise (e.g. the click action is focus rather than drag) these are null.
			if (this.startDrag == null || this.startLocation == null)
			{
				return;
			}

			Point current = this.getScreenLocation(e);
			Point offset = new Point((int) current.getX() - (int) startDrag.getX(), (int) current.getY() - (int) startDrag.getY());
			pipPoint = new Point((int) (this.startLocation.getX() + offset.getX()), (int) (this.startLocation.getY() + offset.getY()));
			target.setLocation(pipPoint);
		}

		public void mouseMoved(MouseEvent e)
		{
		}
	}


	private class pipBar extends JPanel
	{

		int maxLevel;
		int currentLevel;
		Color[] colors;

		private pipBar(int maxLevel, int currentLevel, Color[] colors)
		{
			this.maxLevel = maxLevel;
			this.currentLevel = currentLevel;
			this.colors = colors;
			setOpaque(false);
		}

		private void updateBar(int maxLevel, int currentLevel, Color[] colors)
		{
			this.maxLevel = maxLevel;
			this.currentLevel = currentLevel;
			this.colors = colors;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			g.setComposite(AlphaComposite.SrcOver.derive(paintAlpha(barAlpha())));
			g.setColor(colors[0]);
			g.fillRect(0, 0, config.getBarWidth(), pipHeight);
			int bgHeight = (int) ((maxLevel - currentLevel) * pipHeight / ((float) maxLevel));
			g.setColor(colors[1]);
			g.fillRect(0, 0, config.getBarWidth(), bgHeight);

			if (config.barText() && config.getBarWidth() >= 15)
			{
				g.setFont(FontManager.getRunescapeSmallFont());

				String text = String.valueOf(currentLevel);
				int y = 20;
				int x = config.getBarWidth() / 2 - g.getFontMetrics().stringWidth(text) / 2;

				//text outline
				g.setColor(Color.BLACK);
				g.drawString(text, x, y + 1);
				g.drawString(text, x, y - 1);
				g.drawString(text, x + 1, y);
				g.drawString(text, x - 1, y);

				//text
				g.setColor(Color.WHITE);
				g.drawString(text, x, y);
			}
			g.dispose();
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(config.getBarWidth(), pipHeight);
		}
	}

	// window background, visible as the border around the image and bars
	private class pipPanel extends JPanel
	{
		private pipPanel()
		{
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			g.setComposite(AlphaComposite.SrcOver.derive(paintAlpha(imageAlpha())));
			g.setColor(getBackground());
			g.fillRect(0, 0, getWidth(), getHeight());
			g.dispose();
		}
	}

	private class pipImage extends JLabel
	{
		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			g.setComposite(AlphaComposite.SrcOver.derive(paintAlpha(imageAlpha())));
			super.paintComponent(g);
			g.dispose();
		}
	}

	@Inject
	private ClientUI clientUi;

	@Inject
	private Client client;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private DrawManager drawManager;

	@Inject
	private PictureInPictureConfig config;

	@Inject
	private ConfigManager configManager;

	@Override
	protected void startUp() throws Exception
	{
		log.debug("PIP started!");
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.debug("PIP stopped!");
		destroyPip();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN)
		{
			destroyPip();
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		// checked every tick (not just on redraw) so a PIP that opened late is closed once RL has focus
		focused = clientUi.isFocused();
		if (focused)
		{
			suppressPipUntil = 0;
			if (pipFrame != null)
			{
				destroyPip();
			}
		}
		else if (pipFrame == null && !isPipSuppressed())
		{
			// only open once focus has left RuneLite entirely, not while one of its dialogs is active
			if (!isAnyWindowActive())
			{
				updateHitpoints();
				updatePrayer();
				initializePip();
			}
		}

		if (clientTick % config.redrawRate().getId() == 0)
		{
			clientTick = 0;

			if (!focused && pipFrame != null && pipUp)
			{
				updatePip();
				//log.debug("PIP updated");
			}
		}
		clientTick++;

		//split this from the pip (bars can update more frequently if needed)
		if (!focused && pipFrame != null && pipUp)
		{
			updateHitpoints();
			updatePrayer();
			updateBars();
		}
	}

	@Provides
	PictureInPictureConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PictureInPictureConfig.class);
	}

	private void updateHitpoints()
	{
		currentHealth = client.getBoostedSkillLevel(Skill.HITPOINTS);
		maxHealth = client.getRealSkillLevel(Skill.HITPOINTS);
		healthColor = HEALTH;
		int poisonState = client.getVarpValue(VarPlayer.POISON);
		if (poisonState >= 1000000)
		{
			healthColor = VENOMED;
		}
		else if (poisonState > 0)
		{
			healthColor = POISONED;
		}
		else if (client.getVarpValue(VarPlayer.DISEASE_VALUE) > 0)
		{
			healthColor = DISEASE;
		}
	}

	private void updatePrayer()
	{
		currentPrayer = client.getBoostedSkillLevel(Skill.PRAYER);
		maxPrayer = client.getRealSkillLevel(Skill.PRAYER);
	}

	private void startPip(Image image)
	{

		int position = config.barPosition().getPosition();
		leftSkill = config.leftBar().getSkill();
		rightSkill = config.rightBar().getSkill();

		int offset = getOffset();

		SwingUtilities.invokeLater(new Runnable()
		{
			@Override
			public void run()
			{
				// focus may have returned while this was queued
				if (!pipUp && !clientUi.isFocused() && !isPipSuppressed())
				{

					log.debug("Initializing PIP");

					if (config.limitedDimension().toString().equals("Width"))
					{
						pipWidth = config.targetSize().getWidth();
						pipScale = (double) pipWidth / (double) image.getWidth(null);
						pipHeight = (int) (image.getHeight(null) * pipScale);
					}
					else
					{
						pipHeight = config.targetSize().getHeight();
						pipScale = (double) pipHeight / (double) image.getHeight(null);
						pipWidth = (int) (image.getWidth(null) * pipScale);
					}

					Image img = pipScale(image);
					ImageIcon icon = new ImageIcon(img);

					pipFrame = new JFrame("Picture in Picture");
					pipFrame.setContentPane(new pipPanel());
					pipFrame.setFocusableWindowState(false);
					pipFrame.setType(Window.Type.UTILITY);
					pipFrame.setLayout(new FlowLayout(FlowLayout.LEFT, config.borderWidth(), config.borderWidth()));
					pipFrame.setSize(img.getWidth(null) + offset, img.getHeight(null));
					lbl = new pipImage();
					lbl.setIcon(icon);
					pipFrame.setUndecorated(true);

					//pull in bar info from config
					if (leftSkill == Skill.HITPOINTS)
					{
						leftBar = new pipBar(maxHealth, currentHealth, healthColor);
					}
					else if (leftSkill == Skill.PRAYER)
					{
						leftBar = new pipBar(maxPrayer, currentPrayer, PRAYER);
					}
					if (rightSkill == Skill.HITPOINTS)
					{
						rightBar = new pipBar(maxHealth, currentHealth, healthColor);
					}
					else if (rightSkill == Skill.PRAYER)
					{
						rightBar = new pipBar(maxPrayer, currentPrayer, PRAYER);
					}

					//set the order of bars and pip window
					if (position == 0)
					{
						if (leftSkill != null)
						{
							pipFrame.add(leftBar);
						}
						if (rightSkill != null)
						{
							pipFrame.add(rightBar);
						}
						pipFrame.add(lbl);
					}
					else if (position == 1)
					{
						pipFrame.add(lbl);
						if (leftSkill != null)
						{
							pipFrame.add(leftBar);
						}
						if (rightSkill != null)
						{
							pipFrame.add(rightBar);
						}
					}
					else
					{
						if (leftSkill != null)
						{
							pipFrame.add(leftBar);
						}
						pipFrame.add(lbl);
						if (rightSkill != null)
						{
							pipFrame.add(rightBar);
						}
					}

					pipFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
					pipFrame.setAlwaysOnTop(true);

					MoveMouseListener listener = new MoveMouseListener(pipFrame);
					pipFrame.addMouseListener(listener);
					pipFrame.addMouseMotionListener(listener);

					//get effective screen area
					Rectangle effectiveScreenArea = getEffectiveScreenArea();

					//set location
					if (config.quadrantID().getId() == 1)
					{
						pipPoint.setLocation(effectiveScreenArea.width - pipWidth - config.paddingX() - offset, config.paddingY());
					}
					else if (config.quadrantID().getId() == 2)
					{
						pipPoint.setLocation(config.paddingX(), config.paddingY());
					}
					else if (config.quadrantID().getId() == 3)
					{
						pipPoint.setLocation(config.paddingX(), effectiveScreenArea.height - pipHeight - config.paddingY() - 2 * config.borderWidth());
					}
					else
					{
						pipPoint.setLocation(effectiveScreenArea.width - pipWidth - config.paddingX() - offset, effectiveScreenArea.height - pipHeight - config.paddingY() - 2 * config.borderWidth());
					}

					pipFrame.setLocation(pipPoint);
					setupOpacity();

					// Display the window.
					pipFrame.pack();
					pipFrame.setVisible(true);
					pipUp = true;

					log.debug("PIP initialized");
				}
			}
		});
	}

	private Rectangle getEffectiveScreenArea()
	{
		GraphicsConfiguration gc = clientUi.getGraphicsConfiguration();
		Rectangle bounds = gc.getBounds();
		Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
		Rectangle effectiveScreenArea = new Rectangle();
		effectiveScreenArea.x = bounds.x + screenInsets.left;
		effectiveScreenArea.y = bounds.y + screenInsets.top;
		effectiveScreenArea.height = bounds.height - screenInsets.top - screenInsets.bottom;
		effectiveScreenArea.width = bounds.width - screenInsets.left - screenInsets.right;
		return effectiveScreenArea;
	}

	private void focusClient()
	{
		suppressPipUntil = System.currentTimeMillis() + FOCUS_GRACE_MS;

		// both click actions force focus: ClientUI.requestFocus doesn't activate RL on Windows,
		// so the PIP came back once the grace period ran out
		clientUi.forceFocus();

		destroyPip();
	}

	private boolean isPipSuppressed()
	{
		return System.currentTimeMillis() < suppressPipUntil;
	}

	private boolean isAnyWindowActive()
	{
		for (Window window : Window.getWindows())
		{
			if (window.isActive())
			{
				return true;
			}
		}
		return false;
	}

	private void setupOpacity()
	{
		hovered = false;
		perPixelOpacity = false;
		windowOpacity = false;

		// skip translucency entirely at the defaults so the window behaves exactly as before
		if (config.opacity() == 100 && config.hoverOpacity() == 100 && config.barOpacity() == 100)
		{
			return;
		}

		GraphicsDevice device = pipFrame.getGraphicsConfiguration().getDevice();
		if (device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT))
		{
			// each component paints its own alpha, so the bars can differ from the image
			perPixelOpacity = true;
			pipFrame.setBackground(new Color(0, 0, 0, 0));
		}
		else if (device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT))
		{
			// fallback: one opacity for the whole window, Bar Opacity is ignored
			windowOpacity = true;
		}
		else
		{
			log.debug("PIP opacity not supported on this display");
		}
		applyOpacity();
	}

	private void applyOpacity()
	{
		if (pipFrame == null)
		{
			return;
		}
		if (perPixelOpacity)
		{
			pipFrame.repaint();
		}
		else if (windowOpacity)
		{
			pipFrame.setOpacity(imageAlpha());
		}
	}

	private void setHovered(boolean hovered)
	{
		if (this.hovered != hovered)
		{
			this.hovered = hovered;
			applyOpacity();
		}
	}

	private float imageAlpha()
	{
		return (hovered ? config.hoverOpacity() : config.opacity()) / 100f;
	}

	private float barAlpha()
	{
		return (hovered ? config.hoverOpacity() : config.barOpacity()) / 100f;
	}

	// components only paint their own alpha when the window supports per-pixel translucency
	private float paintAlpha(float alpha)
	{
		return perPixelOpacity ? alpha : 1f;
	}

	private int getOffset()
	{
		return 2 * config.borderWidth() + ((leftSkill != null) ? config.borderWidth() + config.getBarWidth() : 0) + ((rightSkill != null) ? config.borderWidth() + config.getBarWidth() : 0);
	}

	private void destroyPip()
	{
		// run on the EDT so this can't interleave with startPip building the frame
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::destroyPip);
			return;
		}

		// Destroy the PIP Frame
		if (pipFrame != null)
		{
			pipFrame.setVisible(false);
			pipFrame.dispose();
			pipFrame = null;
			pipUp = false;
			log.debug("PIP destroyed");
		}

		// Clear out anything else that gets "stuck"
		for (Frame frame : Frame.getFrames())
		{
			if (frame.getTitle() != null && frame.getTitle().equals("Picture in Picture"))
			{
				frame.setVisible(false);
				frame.dispose();
				frame = null;
				log.debug("PIP cleanup");
			}
		}
	}

	//runs first to initialize pip
	private void initializePip()
	{
		Consumer<Image> imageCallback = (img) ->
		{
			executor.submit(() -> startPip(img));
		};
		drawManager.requestNextFrameListener(imageCallback);
	}

	//updates if pip is already up
	private void updatePip()
	{
		Consumer<Image> imageCallback = (img) ->
		{
			executor.submit(() -> updatePip(img));
		};
		drawManager.requestNextFrameListener(imageCallback);
	}

	//update image
	private void updatePip(Image image)
	{
		SwingUtilities.invokeLater(new Runnable()
		{
			@Override
			public void run()
			{
				Image img = pipScale(image);
				ImageIcon icon = new ImageIcon(img);
				icon.getImage().flush();
				lbl.setIcon(icon);
			}
		});
	}

	//update bars
	private void updateBars()
	{
		SwingUtilities.invokeLater(new Runnable()
		{
			@Override
			public void run()
			{
				if (leftSkill == Skill.HITPOINTS)
				{
					leftBar.updateBar(maxHealth, currentHealth, healthColor);
				}
				else if (leftSkill == Skill.PRAYER)
				{
					leftBar.updateBar(maxPrayer, currentPrayer, PRAYER);
				}
				if (rightSkill == Skill.HITPOINTS)
				{
					rightBar.updateBar(maxHealth, currentHealth, healthColor);
				}
				else if (rightSkill == Skill.PRAYER)
				{
					rightBar.updateBar(maxPrayer, currentPrayer, PRAYER);
				}
			}
		});
	}

	private Image pipScale(Image originalImage)
	{

		int samples = config.renderQuality().getRedraw();
		RenderingHints hints = new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		hints.add(new RenderingHints(RenderingHints.KEY_RENDERING, config.renderQuality().getQuality()));
		hints.add(new RenderingHints(RenderingHints.KEY_INTERPOLATION, config.renderQuality().getHint()));

		if (pipScale > 1)
		{
			return originalImage;
		}

		BufferedImage returnImage = (BufferedImage) originalImage;

		int w = originalImage.getWidth(null);
		int h = originalImage.getHeight(null);
		int incW = (w - pipWidth) / samples;
		int incH = (h - pipHeight) / samples;

		for (int i = 1; i <= samples; i++)
		{

			if (i == samples)
			{
				w = pipWidth;
				h = pipHeight;
			}
			else
			{
				w -= incW;
				h -= incH;
			}

			BufferedImage tempImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g2 = tempImage.createGraphics();
			g2.setRenderingHints(hints);
			g2.drawImage(returnImage, 0, 0, w, h, null);
			g2.dispose();
			returnImage = tempImage;
		}
		return returnImage;
	}
}
