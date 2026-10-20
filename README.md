# Picture in Picture
Allows for displaying RuneLite in "Picture in Picture" mode when focus is elsewhere.

> :warning: **WARNING**: Reduce ***Redraw Rate*** and ***Render Quality*** configuration options to lower CPU usage.

## Configuration Options
![](https://github.com/tcpowell/images/blob/master/picture-in-picture-config.png)

## Sample Screenshot
![](https://github.com/tcpowell/images/blob/master/picture-in-picture-screenshot.png)

## Change Log

### 1.5.0
- Added Opacity setting for the picture in picture image and border
- Added Hover Opacity setting to fade the picture in picture while the mouse is over it
- Added Bar Opacity setting for the status bars
- Fixed PIP reappearing over RuneLite after clicking it to focus
- Fixed errors when dragging the PIP with Click or Shift+Click set to Request Focus or Force Focus
- Updated to the current RuneLite plugin template

### 1.4.2
- Variable tweaks

### 1.4.1
- Updated to initialize picture in picture faster
- Fixed issue where PIP would get "stuck" after returning focus to RL
- Code formatting

### 1.4
- Added configurable Click and Shift+Click options
- Added functionality to drag the picture in picture window to a new position
- General code cleanup

### 1.3
- Added configurable status bars for hitpoints and prayer
- Added configuration option for border width
- Updated configuration panel to use sections

### 1.2
- Added configuration options for picture quality
- Added configuration options to use either "Request Focus" or "Force Focus"

### 1.1
- Added configuration options for redraw rate
- Updated to use RuneLite's "Request Focus" on click (as opposed to Force Focus)

### 1.0.1
- Updated mouse event to return to RuneLite (from mouseClicked to mousePressed)
- Updated icon
