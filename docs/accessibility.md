# Accessibility

## Things Android provides, where the app just allows override

- Voice typing: typed text uses real text fields, so the keyboard's mic works.
- "Remove animations": all motion goes through Compose, which follows that setting.
- Font size: nothing in the app overrides the system size.
- TalkBack, Voice Access, Switch Access and Select to Speak: all controls are labelled, and every icon button has a name.
- Every color swatch has a name, and pages and sections can be moved with TalkBack or Switch Access.
- Screen reader reads text through ocr on pictures.

## App specific

- per-pen stabilization (it smooths out shake)
- hold for a straight line, shapes and the ruler
- drawing with the pen only by default, with a toggle for fingers
- palm rejection
- zoom and undo/redo as buttons and keyboard shortcuts, not just gestures
- a keyboard shortcuts list
- labels when the pen hovers
- page colors
