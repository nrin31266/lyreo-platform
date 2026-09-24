import { render } from '@testing-library/react-native';
import { Text } from 'react-native';

it('renders a native foundation component', async () => {
  const view = await render(<Text>Mobile test rail</Text>);
  expect(view.getByText('Mobile test rail')).toBeTruthy();
});
