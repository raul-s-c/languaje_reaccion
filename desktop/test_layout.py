"""Check action visibility at minimum window size and different display scales."""
import tkinter as tk
import unittest
from unittest.mock import patch
import app


class LayoutTests(unittest.TestCase):
    def test_start_button_remains_visible(self):
        original = tk.Tk
        for scaling in (1.33, 1.67, 2.0):
            with self.subTest(scaling=scaling):
                roots = []

                def factory():
                    root = original()
                    root.tk.call('tk', 'scaling', scaling)
                    roots.append(root)
                    return root

                def inspect(root):
                    root.geometry('850x600')
                    root.update()
                    actions = root.winfo_children()[0]
                    button = actions.winfo_children()[0]
                    self.assertEqual(button.cget('text'), 'Iniciar · Generar .lrpack')
                    self.assertTrue(button.winfo_ismapped())
                    bottom = button.winfo_rooty() - root.winfo_rooty() + button.winfo_height()
                    self.assertLessEqual(bottom, root.winfo_height())
                    self.assertGreater(button.winfo_width(), 100)

                try:
                    with patch.object(tk, 'Tk', side_effect=factory), patch.object(original, 'mainloop', inspect):
                        app.main()
                finally:
                    for root in roots:
                        for callback in root.tk.call('after', 'info'):
                            root.after_cancel(callback)
                        root.destroy()


if __name__ == '__main__':
    unittest.main()
