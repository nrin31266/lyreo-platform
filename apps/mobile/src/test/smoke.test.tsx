import { fireEvent, render } from '@testing-library/react-native';
import { Button } from '../components/ui/button';

it('keeps the themed button accessible and respects disabled state', async () => {
  const onPress = jest.fn();
  const view = await render(<Button onPress={onPress}>Tiếp tục học</Button>);
  fireEvent.press(view.getByRole('button', { name: 'Tiếp tục học' }));
  expect(onPress).toHaveBeenCalledTimes(1);

  await view.rerender(<Button disabled onPress={onPress}>Tiếp tục học</Button>);
  fireEvent.press(view.getByRole('button', { name: 'Tiếp tục học' }));
  expect(onPress).toHaveBeenCalledTimes(1);
});
