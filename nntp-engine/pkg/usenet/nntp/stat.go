package nntp

func (c *Client) StatArticle(messageID string) (bool, error) {
	c.setShortDeadline()

	id, err := c.conn.Cmd("STAT %s", formatMessageID(messageID))
	if err != nil {
		return false, err
	}

	c.conn.StartResponse(id)
	code, _, err := c.conn.ReadCodeLine(223)
	c.conn.EndResponse(id)

	if err != nil {

		if code == 430 {
			return false, nil
		}
		return false, err
	}

	return true, nil
}
